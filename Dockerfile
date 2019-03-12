FROM distribution.flocktory.com/flocktory-jdk11-oracle-onbuild:0.2

#ADD . /opt/build
#
#RUN cd /opt/build && \
#    lein cljsbuild once prod && \
#    lein uberjar && \
#    cp target/chronojob-0.1.0-SNAPSHOT-standalone.jar /opt/ && \
#    cp config/prod.clj /opt/config.clj && \
#    cp config/logback_prod.xml /opt/logback.xml && \
#    cd / && \
#    rm -rf /opt/build

CMD ["lein", "trampoline", "run", "config/dynamic.clj"]