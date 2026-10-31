FROM gcr.io/oss-fuzz-base/base-builder-jvm

# The base image ships an old OpenJDK, but Scala 3.10 and the dependencies need
# JDK 21. Install Temurin 21 for the build; build.sh copies it next to the
# fuzzers so they run on the same JVM.
RUN curl -fL -o /tmp/jdk21.tar.gz \
      "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz" \
 && echo "d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c  /tmp/jdk21.tar.gz" | sha256sum -c \
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
