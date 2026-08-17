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

# V9.5.5 : un cheval dont DA3 a perdu les deux jambes arrière doit récupérer
# quatre appuis à partir des silhouettes, sans supprimer le corps ni la queue.
javac -encoding UTF-8 -cp build/v74-tests -d build/v74-tests \
 "$P/AnimalLegTopologyRefiner.java" \
 validation/java/com/chasmet/modeliseur3d/model/AnimalLegTopologyRefinerSelfTest.java
java -cp build/v74-tests com.chasmet.modeliseur3d.model.AnimalLegTopologyRefinerSelfTest
