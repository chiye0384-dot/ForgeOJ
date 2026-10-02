-- SECURITY_VIOLATION is 18 characters; V3 already allowed it in the CHECK constraint.
ALTER TABLE submission MODIFY COLUMN verdict VARCHAR(32) NULL;
