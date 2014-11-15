#!/bin/sh

set -e

# Usage: ./with-android-sdk.sh command args ...

# This downloads the android SDK if it isn't already downloaded. This requires
# and will download 10 GB of space.

sdkurl=https://srclib-support.s3-us-west-2.amazonaws.com/full-android-sdk.tgz

if [ ! '(' -d /tmp/srclib-android ')' ]
then mkdir -p /tmp/srclib-android
     (cd /tmp/srclib-android; curl $sdkurl | tar xz --strip-components=1)
fi

export ANDROID_HOME=/tmp/srclib-android
export PATH=$ANDROID_HOME/tools:$PATH

$*
