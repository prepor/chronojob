#!/bin/bash

cd /opt/chronojob
export HOME=/root
exec lein trampoline run $FLOCKTORY_ENVIRONMENT_PATH
