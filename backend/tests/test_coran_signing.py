"""Verrouillage de l'identité OIDC pour les canaux Coran et Google Play."""
from backend.coran_signing import validate_claims, CLAIMS, ALLOWED_WORKFLOW_REFS


def identity(workflow):
    return dict(CLAIMS, workflow_ref=workflow, event_name="push")


def test_accepts_only_the_two_published_main_workflows():
    assert len(ALLOWED_WORKFLOW_REFS) == 2
    for workflow in ALLOWED_WORKFLOW_REFS:
        assert validate_claims(identity(workflow))
        assert validate_claims(dict(identity(workflow), event_name="workflow_dispatch"))


def test_rejects_untrusted_workflows_and_branches():
    for workflow in (
        "Chasmet/Coran/.github/workflows/other.yml@refs/heads/main",
        "Chasmet/Coran/.github/workflows/google-play.yml@refs/heads/feature/play-sync30-coran-20261115",
        "Chasmet/Modeliseur-3d/.github/workflows/google-play.yml@refs/heads/main",
    ):
        assert not validate_claims(identity(workflow))


def test_rejects_untrusted_claims_and_events():
    valid = identity(sorted(ALLOWED_WORKFLOW_REFS)[0])
    for key in CLAIMS:
        tampered = dict(valid, **{key: "not-authorized"})
        assert not validate_claims(tampered), key
    for event in ("pull_request", "pull_request_target", "schedule", "repository_dispatch"):
        assert not validate_claims(dict(valid, event_name=event))
