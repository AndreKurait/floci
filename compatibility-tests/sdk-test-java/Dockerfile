FROM maven:3.9-eclipse-temurin-17
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src/ src/
RUN mvn test-compile -q

ENV FLOCI_ENDPOINT=http://floci:4566

RUN mkdir -p /results
COPY run-tests.sh /usr/local/bin/run-sdk-tests
ENTRYPOINT ["bash", "/usr/local/bin/run-sdk-tests"]
