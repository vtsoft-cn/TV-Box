
#!/usr/bin/env sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass any JVM options to Gradle.
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
darwin=false
linux=false
mingw=false
case "`uname`" in
    CYGWIN*) cygwin=true ;;
    Darwin*) darwin=true ;;
    Linux) linux=true ;;
    MINGW*) mingw=true ;;
esac

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done

APP_HOME=`dirname "$PRG"`

# For Cygwin, ensure paths are in UNIX format before anything is touched
if $cygwin ; then
    [ -n "$APP_HOME" ] &&
        APP_HOME=`cygpath --unix "$APP_HOME"`
    [ -n "$JAVA_HOME" ] &&
        JAVA_HOME=`cygpath --unix "$JAVA_HOME"`
    [ -n "$CLASSPATH" ] &&
        CLASSPATH=`cygpath --path --unix "$CLASSPATH"`
fi

# Attempt to set JAVA_HOME if it's not already set.
if [ -z "$JAVA_HOME" ] ; then
    if $darwin ; then
        if [ -x '/usr/libexec/java_home' ] ; then
            JAVA_HOME=`/usr/libexec/java_home`
        elif [ -d "/System/Library/Frameworks/JavaVM.framework/Versions/CurrentJDK/Home" ]; then
            JAVA_HOME="/System/Library/Frameworks/JavaVM.framework/Versions/CurrentJDK/Home"
        fi
    else
        javaPath="`which java 2>/dev/null`"
        if [ -n "$javaPath" ] ; then
            javaPath="`readlink -f "$javaPath" 2>/dev/null`"
            JAVA_HOME="`dirname "$javaPath" 2>/dev/null`"
            JAVA_HOME="`cd "$JAVA_HOME/.." && pwd`"
        fi
    fi
fi

# Check for 'java' and 'jar' in JAVA_HOME
if [ -n "$JAVA_HOME" ] ; then
    if [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -x "$JAVA_HOME/bin/jar" ] ; then
        die "ERROR: 'java' or 'jar' not found in $JAVA_HOME/bin. Please set JAVA_HOME correctly." 
    fi
fi

# If JAVA_HOME is not defined, we will be unable to build with Gradle.
if [ -z "$JAVA_HOME" ] ; then
    die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.\n\nPlease set the JAVA_HOME variable in your environment to match the\nlocation of your Java installation." 
fi

# Set APP_OPTS
if [ -z "$APP_OPTS" ]; then
    APP_OPTS="$DEFAULT_JVM_OPTS"
fi

# Add the jar to the CLASSPATH
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Split up the JVM options only where necessary
if $cygwin ; then
    # Add the jar to the CLASSPATH
    CLASSPATH=`cygpath --path --windows "$CLASSPATH"`
fi

# Execute Gradle
exec "$JAVA_HOME/bin/java" $APP_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
