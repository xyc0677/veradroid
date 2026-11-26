#!/bin/sh
if [ -f /mnt/base-us/veradroid/boot/veradroid.sh ]; then
    exec setsid sh /mnt/base-us/veradroid/boot/veradroid.sh home </dev/null >/dev/null 2>&1
fi
exec sh "$(dirname "$0")/install.sh"
