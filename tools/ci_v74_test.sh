#!/usr/bin/env bash
set -euo pipefail
P=app/src/main/java/com/chasmet/modeliseur3d/model
mkdir -p build/v74-tests

javac -encoding UTF-8 -d build/v74-tests \
 "$P/SubjectCategory.java" "$P/HumanoidVolumeRefiner.java" "$P/StylizedFourViewProjector.java" \
 "$P/DepthFusionPolicy.java" "$P/DepthV74Runs.java" "$P/DepthV74View.java" "$P/DepthV74Shape.java" \
 "$P/DepthV74Gate.java" "$P/DepthV74Ray.java" "$P/DepthV74Engine.java" "$P/MultiViewDepthFusion.java" \
 validation/java/com/chasmet/modeliseur3d/model/V74CorrespondenceSelfTest.java
java -cp build/v74-tests com.chasmet.modeliseur3d.model.V74CorrespondenceSelfTest

# Les passes d'intégrité utilisent MemoryDiagnostics qui importe android.os.Debug.
# On compile donc ces tests avec android.jar, sans changer le code runtime.
ANDROID_JAR="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platforms/android-34/android.jar"
test -f "$ANDROID_JAR"

javac -encoding UTF-8 -cp "build/v74-tests:$ANDROID_JAR" -d build/v74-tests \
 "$P/MemoryDiagnostics.java" \
 "$P/AnimalLegTopologyRefiner.java" \
 "$P/CharacterLimbIntegrityRefiner.java" \
 validation/java/com/chasmet/modeliseur3d/model/AnimalLegTopologyRefinerSelfTest.java \
 validation/java/com/chasmet/modeliseur3d/model/CharacterLimbIntegrityRefinerSelfTest.java

java -cp "build/v74-tests:$ANDROID_JAR" com.chasmet.modeliseur3d.model.AnimalLegTopologyRefinerSelfTest
java -cp "build/v74-tests:$ANDROID_JAR" com.chasmet.modeliseur3d.model.CharacterLimbIntegrityRefinerSelfTest
