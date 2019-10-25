package hudson.plugins.parameterizedtrigger.BuildInfoExporterAction

import static hudson.plugins.parameterizedtrigger.ParameterizedTriggerUtils.DISABLE_ACTION_VIEWS_KEY

if (System.getProperty(DISABLE_ACTION_VIEWS_KEY) != null) {
	return
}

def builds = my.triggeredBuilds
if(builds.size() > 0) {
	h2("Subproject Builds")

	ul(style:"list-style-type: none;") {
		for (item in builds.sort{it != null && it.hasProperty('description') ? it.description : ''}) {
//		for (item in builds.sort{it.timeInMillis}) {
			li {
				if (item != null) {

					a(href:"${rootURL}/${item.project.url}", class:"model-link") {
						text(item.project.displayName)
					}
					a(href:"${rootURL}/${item.url}", class:"model-link") {
						img(src:"${imagesURL}/16x16/${item.buildStatusUrl}",
								alt:"${item.iconColor.description}", height:"16", width:"16")
						text(item.displayName)
					}

					if (item.hasProperty('description') && item.description != null && item.description != '') {
						raw(' ' + item.description)
	//						item.properties.each { prop, val ->
	//							text(prop + '=' + val + " <br/> ")
	//						}
					}
				}
			}
		}
	}
}

def projects = my.triggeredProjects
if (projects.size() > 0) {
	h2("Subprojects triggered but not blocked for")

	ul(style:"list-style-type: none;") {
		for (item in projects) {
			li {
				if (item != null) {
					a(href:"${rootURL}/${item.url}", class:"model-link") {
						text(item.displayName)
					}
				}
			}
		}
	}
}

