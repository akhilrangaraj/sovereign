FROM anapsix/alpine-java:8_jdk as builder
ENV sbt_version 1.2.1
ENV sbt_home /usr/local/sbt
ENV PATH ${PATH}:${sbt_home}/bin
RUN apk add --no-cache --update bash wget && \
    mkdir -p "$sbt_home" && \
    wget -qO - --no-check-certificate "https://github.com/sbt/sbt/releases/download/v$sbt_version/sbt-$sbt_version.tgz" | tar xz -C $sbt_home --strip-components=1 && \
    apk del wget && \
    sbt sbtVersion
RUN mkdir -p /app
RUN mkdir -p /app/project
WORKDIR /app
COPY build.sbt /app
COPY project/plugins.sbt /app/project/
COPY project/build.properties /app/project/
# Grab all of our dependencies
RUN sbt compile
# Assemble our project
COPY . /app
RUN sbt compile
RUN ["sbt", "set test in assembly := {}", "assembly"]

FROM anapsix/alpine-java:8
RUN mkdir -p /app
RUN apk --no-cache add openssl wget
RUN wget -O /usr/local/bin/dumb-init https://github.com/Yelp/dumb-init/releases/download/v1.2.1/dumb-init_1.2.1_amd64
RUN chmod +x /usr/local/bin/dumb-init
COPY --from=builder /app/target/scala-2.12/sovereign-assembly-0.1.jar /app/sovereign-assembly-0.1.jar
ENTRYPOINT ["/usr/local/bin/dumb-init", "--"]

