# PractiTest xBot

`ant uberjar` will build the xbot-all.jar
`java -jar xbot-all.jar` will run the application

xBot runs local HTTP server for configuration and logger UI. Default listening port is 18080. It can be changed by setting `com.practitest.xbot.listening_port`:

`java -Dcom.practitest.xbot.listening_port=28080 -jar xbot-all.jar`

Local HTTP listener endpoints:

* `/preferences` - configuration interface. Configuration is stored in $HOME/xbot.properties.
* `/log` - Log

When system tray is available, xBot will inject it's icon into the tray. The HTTP endpoints are available in the icon menu.
