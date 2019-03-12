FROM distribution.flocktory.com/flocktory-jdk11-oracle-onbuild:0.2

CMD ["lein", "trampoline", "run", "config/dynamic.clj"]