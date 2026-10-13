import pytest
from starlette.exceptions import HTTPException
from backend.signing import validate_claims, REF, WORKFLOW

def claims():
    return {'repository': 'Chasmet/Modeliseur-3d', 'repository_id': '1320257918',
            'repository_owner_id': '160381695', 'actor_id': '160381695',
            'ref': REF, 'workflow_ref': WORKFLOW, 'runner_environment': 'github-hosted', 'event_name': 'push'}

def test_only_owner_release_workflow_can_get_the_key():
    validate_claims(claims())
    for key in claims():
        changed = claims(); changed[key] = 'attacker'
        with pytest.raises(HTTPException): validate_claims(changed)
    changed = claims(); changed['event_name'] = 'pull_request'
    with pytest.raises(HTTPException): validate_claims(changed)
