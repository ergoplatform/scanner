FROM mozilla/sbt:8u292_1.5.4 as builder
WORKDIR /mnt
COPY build.sbt ./
COPY project/ project/
RUN sbt update
COPY app/ app/
COPY conf/ conf/
RUN sbt assembly
RUN mv `find target/scala-*/ -name scanner-*.jar` scanner.jar

FROM openjdk:8-jre-slim
RUN adduser --disabled-password --home /home/ergo --uid 9052 --gecos "ErgoPlatform" ergo && \
    install -m 0750 -o ergo -g ergo -d /home/ergo/database
USER ergo
EXPOSE 9000
WORKDIR /home/ergo
VOLUME ["/home/ergo/database"]
COPY --from=builder /mnt/scanner.jar /home/ergo/scanner.jar
COPY  --from=builder /mnt/conf/application.conf /home/ergo/application.conf
ENTRYPOINT java -jar -D"config.file"=./application.conf /home/ergo/scanner.jar
