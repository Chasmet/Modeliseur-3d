from backend.lesfaits_signing import CLAIMS, ALLOWED_WORKFLOW_REFS, validate_claims

def test_exact_workflow_only():
    allowed = dict(CLAIMS, workflow_ref=next(iter(ALLOWED_WORKFLOW_REFS)), event_name="push")
    assert validate_claims(allowed)
    for field, value in (("repository","Chasmet/CHK-Agent-Browser"),("actor_id","1"),("ref","refs/heads/test"),("workflow_ref","wrong"),("event_name","pull_request")):
        assert not validate_claims(dict(allowed, **{field:value}))
