FROM ktis-bastion01.container.ipc.kt.com:5000/alpine/openjdk-8:latest

ENV JAVA_OPTS="-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -XX:MaxRAMFraction=1 -XshowSettings:vm"
ENV JAVA_OPTS="${JAVA_OPTS} -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+G1SummarizeConcMark -XX:InitiatingHeapOccupancyPercent=35 -XX:G1ConcRefinementThreads=20"

ARG sourceFile
ENV targetFile app.jar

ADD $sourceFile $targetFile
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar $targetFile"]
