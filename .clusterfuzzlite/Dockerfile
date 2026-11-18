FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:bd13a763de1a0fb22099442094e7e0049657a8c47f01e9d7af8bb849e0ad4699

# The base image ships an old OpenJDK, but Scala 3.10 and the dependencies need
# JDK 21. Install Amazon Corretto 21 for the build; build.sh copies it next to
# the fuzzers so they run on the same JVM.
RUN curl -fL -o /tmp/jdk21.tar.gz \
      "https://corretto.aws/downloads/resources/21.0.13.1.2/amazon-corretto-21.0.13.1.2-linux-x64.tar.gz" \
 && echo "2aaa2a9a43143b20ff0b28c8d0da27407b97dc40b160d9a2f60a26965815e948  /tmp/jdk21.tar.gz" | sha256sum -c \
 && mkdir -p /opt/jdk21 \
 && tar -xzf /tmp/jdk21.tar.gz -C /opt/jdk21 --strip-components=1 \
 && rm /tmp/jdk21.tar.gz

# sbt via Coursier (same pinned launcher as the CI workflows).
RUN curl -fL https://github.com/coursier/coursier/releases/download/v2.1.24/cs-x86_64-pc-linux.gz | gzip -d > /usr/local/bin/cs \
 && echo '1517a0b6c4b9608dc45da8f34bc8290707ed50104ee92662f57808d2c012be54  /usr/local/bin/cs' | sha256sum -c \
 && chmod +x /usr/local/bin/cs \
 && JAVA_HOME=/opt/jdk21 cs install --install-dir /usr/local/bin sbt

COPY . $SRC/parqueteer
COPY .clusterfuzzlite/build.sh $SRC/
WORKDIR $SRC/parqueteer
