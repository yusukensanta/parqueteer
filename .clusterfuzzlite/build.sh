#!/bin/bash -eu
# Builds the Jazzer fuzz targets in src/test/scala/.../fuzz and writes one
# wrapper script per target into $OUT.

export JAVA_HOME=/opt/jdk21
export PATH="$JAVA_HOME/bin:$PATH"

cd "$SRC/parqueteer"
sbt -batch Test/compile "export Test/fullClasspath" | tee /tmp/sbt.out
CLASSPATH_LINE=$(grep '^/' /tmp/sbt.out | tail -1)

# Fuzzers run from $OUT on the JDK shipped alongside them, with the jars and
# compiled classes they need copied next to the wrapper.
mkdir -p "$OUT/lib" "$OUT/classes"
cp -r "$JAVA_HOME" "$OUT/jdk"
RUNTIME_CLASSPATH='$this_dir/classes'
index=0
IFS=':' read -ra ENTRIES <<< "$CLASSPATH_LINE"
for entry in "${ENTRIES[@]}"; do
  if [ -d "$entry" ]; then
    cp -r "$entry"/. "$OUT/classes/"
  elif [ -f "$entry" ]; then
    index=$((index + 1))
    name=$(printf '%04d-%s' "$index" "$(basename "$entry")")
    cp "$entry" "$OUT/lib/$name"
    RUNTIME_CLASSPATH="$RUNTIME_CLASSPATH:\$this_dir/lib/$name"
  fi
done

for fuzzer in FilterParserFuzzer CsvParserFuzzer LtsvParserFuzzer SizeParserFuzzer; do
  echo "#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname \"\$0\")
if [[ \"\$@\" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
JAVA_HOME=\$this_dir/jdk LD_LIBRARY_PATH=\"\$this_dir/jdk/lib/server\":\$this_dir \
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \
--cp=$RUNTIME_CLASSPATH \
--target_class=io.github.yusukensanta.parqueteer.fuzz.$fuzzer \
--jvm_args=\"\$mem_settings:-Djava.awt.headless=true\" \
\$@" > "$OUT/$fuzzer"
  chmod +x "$OUT/$fuzzer"
done
