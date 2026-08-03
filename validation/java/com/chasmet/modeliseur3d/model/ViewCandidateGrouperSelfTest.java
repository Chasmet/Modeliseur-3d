package com.chasmet.modeliseur3d.model;

import java.util.ArrayList;
import java.util.List;

/** Test JVM sans dépendance Android ni bibliothèque externe. */
public final class ViewCandidateGrouperSelfTest {
    private ViewCandidateGrouperSelfTest() {
    }

    public static void main(String[] args) {
        detachedHumanoidIsOneView();
        spiderPartsStayTogether();
        rotationSheetKeepsThreeViews();
        gridSheetKeepsFourViews();
        System.out.println("ViewCandidateGrouperSelfTest: 4 scénarios réussis");
    }

    private static void detachedHumanoidIsOneView() {
        List<ViewCandidateGrouper.Piece> pieces = new ArrayList<>();
        pieces.add(piece(0, 8_000, 210, 180, 390, 610));
        pieces.add(piece(1, 1_200, 250, 105, 350, 190));
        pieces.add(piece(2, 900, 150, 260, 220, 500));
        pieces.add(piece(3, 900, 380, 260, 450, 500));
        pieces.add(piece(4, 1_500, 235, 600, 295, 900));
        pieces.add(piece(5, 1_500, 305, 600, 365, 900));
        check(
                ViewCandidateGrouper.group(pieces, 600, 1024).size() == 1,
                "Le personnage détaché a été pris pour plusieurs vues"
        );
    }

    private static void spiderPartsStayTogether() {
        List<ViewCandidateGrouper.Piece> pieces = new ArrayList<>();
        pieces.add(piece(0, 5_800, 250, 350, 550, 570));
        int id = 1;
        for (int row = 0; row < 2; row++) {
            int top = 340 + row * 130;
            pieces.add(piece(id++, 520, 90, top, 270, top + 65));
            pieces.add(piece(id++, 520, 530, top, 710, top + 65));
            pieces.add(piece(id++, 420, 150, top - 65, 310, top + 20));
            pieces.add(piece(id++, 420, 490, top - 65, 650, top + 20));
        }
        check(
                ViewCandidateGrouper.group(pieces, 800, 800).size() == 1,
                "Les pattes de l'animal ont été prises pour des vues"
        );
    }

    private static void rotationSheetKeepsThreeViews() {
        List<ViewCandidateGrouper.Piece> pieces = new ArrayList<>();
        pieces.add(piece(0, 9_000, 80, 120, 250, 900));
        pieces.add(piece(1, 8_400, 390, 125, 555, 900));
        pieces.add(piece(2, 6_200, 720, 130, 830, 900));
        pieces.add(piece(3, 260, 245, 405, 275, 470));
        pieces.add(piece(4, 240, 555, 410, 585, 475));
        pieces.add(piece(5, 210, 825, 420, 850, 480));
        List<ViewCandidateGrouper.Group> groups =
                ViewCandidateGrouper.group(pieces, 930, 1024);
        check(groups.size() == 3, "La planche de trois vues a été fusionnée");
    }

    private static void gridSheetKeepsFourViews() {
        List<ViewCandidateGrouper.Piece> pieces = new ArrayList<>();
        pieces.add(piece(0, 5_000, 80, 60, 250, 430));
        pieces.add(piece(1, 4_800, 390, 65, 555, 430));
        pieces.add(piece(2, 5_100, 85, 570, 250, 940));
        pieces.add(piece(3, 4_600, 405, 575, 535, 940));
        List<ViewCandidateGrouper.Group> groups =
                ViewCandidateGrouper.group(pieces, 640, 1024);
        check(groups.size() == 4, "La planche en grille a été fusionnée");
    }

    private static ViewCandidateGrouper.Piece piece(
            int id,
            int pixels,
            int left,
            int top,
            int right,
            int bottom
    ) {
        return new ViewCandidateGrouper.Piece(
                id,
                pixels,
                left,
                top,
                right,
                bottom
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
