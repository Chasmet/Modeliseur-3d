"""OIDC isolation checks: a camera workflow cannot masquerade as any other app."""
from backend.camera_signing import EXPECTED, validate_claims


def good():
    return {**EXPECTED, "event_name": "push"}


def test_only_own_workflow():
    assert validate_claims(good())
    assert validate_claims({**good(), "event_name": "workflow_dispatch"})


def test_cross_app_forbidden():
    for field, value in (
        ("repository", "Chasmet/Coran"),
        ("repository_id", "1456244792"),
        ("workflow_ref", "Chasmet/Camera-maison/.github/workflows/unsafe.yml@refs/heads/main"),
        ("ref", "refs/heads/feature"),
        ("actor_id", "000000"),
        ("runner_environment", "self-hosted"),
        ("event_name", "pull_request"),
    ):
        claims = good()
        claims[field] = value
        assert not validate_claims(claims), field


def test_missing_claim_refused():
    for field in EXPECTED:
        claims = good()
        del claims[field]
        assert not validate_claims(claims), field
