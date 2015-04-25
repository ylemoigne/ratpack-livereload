# ratpack-livereload
Provide a livereload server based on ratpack. Mainly intended to be embedded in another ratpack app.

Usage
------
    LiveReloadServer liveReloadServer = new LiveReloadServer(serverConfig.getBaseDir()
                    .getFile()
                    .resolve("app"));
    liveReloadServer.start();


Dependency.
------

Gradle

    repositories {
        maven {
            url "http://dl.bintray.com/ylemoigne/maven"
        }
    }

    dependencies {
        compile 'fr.javatic.ratpack:ratpack-livereload:0.1'
    }

Changelog.
------
0.1   : Initial Release