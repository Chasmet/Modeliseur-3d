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
