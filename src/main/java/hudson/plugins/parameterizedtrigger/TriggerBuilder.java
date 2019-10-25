/*
 * The MIT License
 *
 * Copyright (c) 2010-2011, InfraDNA, Inc., Manufacture Francaise des Pneumatiques Michelin,
 * Romain Seguy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.parameterizedtrigger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import hudson.*;
import hudson.console.HyperlinkNote;
import hudson.console.ModelHyperlinkNote;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.DependencyGraph;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Cause;
import hudson.model.Cause.UpstreamCause;
import hudson.util.IOException2;
import hudson.util.RunList;
import jenkins.model.DependencyDeclarer;
import org.jvnet.libpam.impl.PAMLibrary;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.stapler.DataBoundConstructor;
import java.io.IOException;
import java.lang.RuntimeException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.Iterator;
import java.util.Collection;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;

/**
 * {@link Builder} that triggers other projects and optionally waits for their completion.
 *
 * @author Kohsuke Kawaguchi
 */
public class TriggerBuilder extends Builder implements DependencyDeclarer {

    private final ArrayList<BlockableBuildTriggerConfig> configs;

    @DataBoundConstructor
    public TriggerBuilder(List<BlockableBuildTriggerConfig> configs) {
        this.configs = new ArrayList<BlockableBuildTriggerConfig>(Util.fixNull(configs));
    }

    public TriggerBuilder(BlockableBuildTriggerConfig... configs) {
        this(Arrays.asList(configs));
    }

    public List<BlockableBuildTriggerConfig> getConfigs() {
        return configs;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                           BuildListener listener) throws InterruptedException, IOException {
        EnvVars env = build.getEnvironment(listener);
        env.overrideAll(build.getBuildVariables());

        boolean buildStepResult = true;

        try {
            for (BlockableBuildTriggerConfig config : configs) {
                ListMultimap<Job, QueueTaskFuture<AbstractBuild>> futures = config.perform3(build, launcher, listener);
                // Only contains resolved projects
                List<Job> projectList = config.getJobs(build.getRootBuild().getProject().getParent(), env);

                // Get the actual defined projects
                StringTokenizer tokenizer = new StringTokenizer(config.getProjects(env), ",");

                if (tokenizer.countTokens() == 0) {
                    throw new AbortException("Build aborted. No projects to trigger. Check your configuration!");
                } else if (tokenizer.countTokens() != projectList.size()) {

                    int nbrOfResolved = tokenizer.countTokens() - projectList.size();

                    // Identify the unresolved project(s)
                    Set<String> unsolvedProjectNames = new TreeSet<String>();
                    while (tokenizer.hasMoreTokens()) {
                        unsolvedProjectNames.add(tokenizer.nextToken().trim());
                    }
                    for (Job project : projectList) {
                        unsolvedProjectNames.remove(project.getFullName());
                    }

                    // Present the undefined project(s) in error message
                    StringBuilder missingProject = new StringBuilder();
                    for (String projectName : unsolvedProjectNames) {
                        missingProject.append(" > ");
                        missingProject.append(projectName);
                        missingProject.append("\n");
                    }

                    throw new AbortException("Build aborted. Can't trigger undefined projects. " + nbrOfResolved + " of the below project(s) can't be resolved:\n" + missingProject.toString() + "Check your configuration!");
                } else {
                    //handle non-blocking configs
                    if (futures.isEmpty()) {
                        listener.getLogger().println("Triggering projects: " + getProjectListAsString(projectList));
                        for (Job p : projectList) {
                            BuildInfoExporterAction.addBuildInfoExporterAction(build, p.getFullName());
                        }
                        continue;
                    }
                    //handle blocking configs
                    for (Job p : projectList) {
                        //handle non-buildable projects
                        if (!config.canBeScheduled(p)) {
                            listener.getLogger().println("Skipping " + HyperlinkNote.encodeTo('/' + p.getUrl(), p.getFullDisplayName()) +
                                    ". The project is either disabled,"
                                    + " or the authenticated user " + ModelHyperlinkNote.encodeTo(User.current()) + " has no Item.BUILD permissions,"
                                    + " or the configuration has not been saved yet.");
                            continue;
                        }

                        List<Future<Run>> runs = futures.get(p);
                        try {
                            listener.getLogger().println("Waiting for the completion of " + HyperlinkNote.encodeTo('/' + p.getUrl(), p.getFullDisplayName()) + " (" + runs.size() +")");
                            while (!runs.isEmpty()) {
                                List<Future<Run>> total_runs = new ArrayList<>(runs);
                                for (Future<Run> future : total_runs)
                                {
                                    try {
                                        if (future != null) {
                                            if (!future.isDone()) {
                                                try {
                                                    Thread.sleep(100);
                                                } catch (InterruptedException ex) {
                                                    throw ex;
                                                }
                                                continue;
                                            }
                                            runs.remove(future);
                                            Run b = future.get();
                                            listener.getLogger().println(HyperlinkNote.encodeTo('/' + b.getUrl(), b.getFullDisplayName()) + " completed. Result was " + b.getResult());
                                            BuildInfoExporterAction.addBuildInfoExporterAction(build, b.getParent().getFullName(), b.getNumber(), b.getResult());

                                            if (buildStepResult && config.getBlock().mapBuildStepResult(b.getResult())) {
                                                build.setResult(config.getBlock().mapBuildResult(b.getResult()));
                                            } else {
                                                buildStepResult = false;
                                            }
                                        } else {
                                            listener.getLogger().println("Skipping " + ModelHyperlinkNote.encodeTo(p) + ". The project was not triggered by some reason.");
                                        }
                                    } catch (CancellationException x) {
                                        listener.getLogger().println("An instance of " + p.getFullDisplayName() +  " was cancelled");
                                        throw new AbortException(p.getFullDisplayName() + " aborted.");
                                    }
                                }
                            }
                        } catch (InterruptedException y) {
                            Queue buildQueue = Jenkins.getInstance().getQueue();
                            int numQueueAborted = 0;
                            for (Queue.Item queueItem : buildQueue.getItems()) {
                                List<Cause> causes = queueItem.getCauses();
                                Cause buildQueueCause = null;
                                boolean userQueueCause = false;
                                for (Cause c : causes) {
                                    if (c instanceof Cause.UserIdCause || c instanceof Cause.UserCause || c instanceof Cause.RemoteCause) {
                                        userQueueCause = true;
                                        break;
                                    }
                                    if ((c instanceof UpstreamCause)) {
                                        buildQueueCause = c;
                                    }
                                }
                                if (!userQueueCause && buildQueueCause != null) {
                                    UpstreamCause upstreamCause = (UpstreamCause) buildQueueCause;
                                    if (upstreamCause.pointsTo(build)) {
                                        numQueueAborted++;
                                        boolean cancelled = buildQueue.cancel(queueItem);
                                        if (cancelled) {
                                            listener.getLogger().println("Removed item from Queue (Reason for queueing: " + queueItem.getWhy() + ")");
                                        }
                                    }
                                }
                                if (numQueueAborted == runs.size()) {
                                    throw y;
                                }
                            }
                            RunList runList = p.getBuilds();
                            int numAborted = 0;
                            for (Iterator it = runList.iterator(); it.hasNext(); ) {
                                Run latestBuild = (Run) it.next();
                                List<Cause> buildCauses = latestBuild.getCauses();
                                Cause.UpstreamCause buildCause = null;
                                boolean userCause = false;
                                for (Cause c : buildCauses) {
                                    if (c instanceof Cause.UserIdCause || c instanceof Cause.UserCause || c instanceof Cause.RemoteCause) {
                                        userCause = true;
                                        break;
                                    }
                                    if ((c instanceof Cause.UpstreamCause)) {
                                        buildCause = (Cause.UpstreamCause) c;
                                    }
                                }
                                if (!userCause && buildCause != null) {
                                    if (buildCause.pointsTo(build)) {
                                        if (latestBuild.isBuilding()) {
                                            numAborted++;
                                            latestBuild.setResult(Result.ABORTED);
                                            latestBuild.getExecutor().doStop();
                                            listener.getLogger().println("Aborted " + HyperlinkNote.encodeTo('/' + latestBuild.getUrl(), latestBuild.getFullDisplayName()));
                                            BuildInfoExporterAction.addBuildInfoExporterAction(build, latestBuild.getParent().getFullName(), latestBuild.getNumber(), latestBuild.getResult());
                                        }
                                    }
                                }
                                if (numAborted + numQueueAborted == runs.size()) {
                                    throw y;
                                }
                            }
                            throw y;
                        }
                    }
                }
            }
        } catch (ExecutionException e) {
            throw new IOException(e); // can't happen, I think.
        }

        return buildStepResult;
    }

    // Public but restricted so we can add tests without completely changing the tests package
    @Restricted(value=org.kohsuke.accmod.restrictions.NoExternalUse.class)
    public String getProjectListAsString(List<Job> projectList){
        StringBuilder projectListString = new StringBuilder();
        for (Iterator<Job> iterator = projectList.iterator(); iterator.hasNext();) {
            Job project = iterator.next();
            projectListString.append(HyperlinkNote.encodeTo('/'+ project.getUrl(), project.getFullDisplayName()));
            if(iterator.hasNext()){
                projectListString.append(", ");
            }
        }
        return projectListString.toString();
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
        return ImmutableList.of(new SubProjectsAction(project, configs));
    }

    private boolean canDeclare(AbstractProject owner) {
        // See HUDSON-5679 -- dependency graph is also not used when triggered from a promotion
        return !owner.getClass().getName().equals("hudson.plugins.promoted_builds.PromotionProcess");
    }

    @Override
    public void buildDependencyGraph(AbstractProject owner, DependencyGraph graph) {
        if (!canDeclare(owner)) return;

        for (BuildTriggerConfig config : configs) {
            List<AbstractProject> projectList = config.getProjectList(owner.getParent(), null);
            for (AbstractProject project : projectList) {
                graph.addDependency(new TriggerBuilderDependency(owner, project, config));
            }
        }
    }

    public static class TriggerBuilderDependency extends ParameterizedDependency {
        public TriggerBuilderDependency(AbstractProject upstream, AbstractProject downstream, BuildTriggerConfig config) {
            super(upstream, downstream, config);
        }

        @Override
        public boolean shouldTriggerBuild(AbstractBuild build, TaskListener listener, List<Action> actions) {
            return false;
        }
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public String getDisplayName() {
            return "Trigger/call builds on other projects";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
