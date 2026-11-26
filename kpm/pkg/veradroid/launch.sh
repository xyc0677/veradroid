#!/bin/sh
[ -f /mnt/base-us/veradroid/boot/veradroid.sh ] && exec setsid sh /mnt/base-us/veradroid/boot/veradroid.sh home </dev/null >/dev/null 2>&1
