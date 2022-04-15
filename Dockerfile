FROM distribution.flocktory.com/flocktory-jdk11-oracle-onbuild:0.2

# For running offline without retrieving deps from Nexus at runtime.
RUN find /root/.m2 -name _remote.repositories -type f -delete

CMD ["lein", "-o", "trampoline", "run", "config/dynamic.clj"]