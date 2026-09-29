ALTER TABLE submission
    DROP CHECK chk_submission_verdict,
    ADD CONSTRAINT chk_submission_verdict CHECK (
        verdict IS NULL OR verdict IN ('AC', 'WA', 'CE', 'RE', 'TLE', 'OLE')
    );
