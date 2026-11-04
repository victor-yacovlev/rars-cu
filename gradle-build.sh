#!/bin/bash
if git submodule status | grep \( > /dev/null ; then
    jar_name="rars-cu-$(date +%Y%m%d).jar"
    if gradle build --no-daemon && cp "build/libs/$jar_name" "$jar_name"; then
        echo "Created $jar_name"
    fi
else
    echo "It looks like JSoftFloat is not cloned. Consider running \"git submodule update --init\""
fi
