ALTER TABLE source_versions ADD COLUMN git_branch VARCHAR(255);

ALTER TABLE source_versions
    ADD CONSTRAINT source_versions_git_branch_ck CHECK (connector_type <> 'GIT' OR (git_branch IS NOT NULL AND git_branch <> ''));

CREATE INDEX source_versions_git_lookup_idx
    ON source_versions (space_id, source_id, connector_type, version_no DESC);
