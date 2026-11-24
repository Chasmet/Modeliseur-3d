import pytest
from starlette.exceptions import HTTPException
from backend.lesfaits_signing import validate, REPOSITORY, WORKFLOW

def claims():
    return {"repository":REPOSITORY,"repository_id":"1467654431","repository_owner_id":"160381695","actor_id":"160381695","ref":"refs/heads/main","workflow_ref":WORKFLOW,"runner_environment":"github-hosted","event_name":"push"}

def test_authorized_workflow():
    validate(claims())

@pytest.mark.parametrize("field,value",[("repository","Chasmet/CHK-Agent-Browser"),("repository_id","1410631793"),("workflow_ref","wrong"),("ref","refs/heads/test"),("actor_id","1"),("event_name","pull_request"),("runner_environment","self-hosted")])
def test_reject_cross_repository_or_wrong_workflow(field,value):
    data=claims();data[field]=value
    with pytest.raises(HTTPException) as e:
        validate(data)
    assert e.value.status_code==403
