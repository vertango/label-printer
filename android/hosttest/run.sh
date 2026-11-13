#!/bin/sh
# Off-device tests for the pure-Java parts (layout, 1 KB zlib, printer commands).
set -e
cd "$(dirname "$0")/.."
P=app/src/main/java/io/github/vertango/labelprinter
OUT=$(mktemp -d)
javac -d "$OUT" $P/Deflate1K.java $P/LabelRaster.java $P/Tspl.java $P/LabelPipeline.java $P/SkuStamp.java $P/SkuMatcher.java hosttest/*.java
java -cp "$OUT" HostTest "$OUT/job.bin"
java -cp "$OUT" StampTest
