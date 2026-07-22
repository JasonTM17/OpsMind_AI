from datetime import datetime, timedelta
from uuid import uuid4

import pytest
from pydantic import ValidationError

from opsmind_ai_runtime.domain.analysis_contracts import DataClassification, DelegatedCapability


def test_capability_rejects_timezone_naive_signed_times() -> None:
    now = datetime.now()
    with pytest.raises(ValidationError, match="timezone"):
        DelegatedCapability(
            issuer="opsmind-platform-api",
            subject="operator:test",
            audience="opsmind-ai-runtime",
            tenant_id=uuid4(),
            incident_id=uuid4(),
            run_id=uuid4(),
            purpose="incident_investigation",
            allowed_data_classes={DataClassification.REDACTED_METRICS},
            request_digest="sha256:" + "a" * 64,
            nonce="nonce-1234567890",
            issued_at=now,
            expires_at=now + timedelta(minutes=1),
        )
