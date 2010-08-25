#!/bin/sh

# to compile just run : "ant dist"
# then upload via this deploy file
# then download from: http://www.practitest.com/files/xbot/xbot.jnlp

BASE_PATH='/var/lib/rails-apps/trunk'
XBOT_LOCATION='/var/lib/rails-apps/trunk/radiant_site/public/files/xbot/lib/'

scp build/dist/xbot.jar deploy@www.practitest.com:$XBOT_LOCATION
