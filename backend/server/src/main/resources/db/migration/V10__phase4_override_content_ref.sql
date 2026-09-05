-- Persist only the opaque address of replacement content; the replacement body
-- remains outside the relational database.
ALTER TABLE chunk_overrides
    ADD COLUMN replacement_content_ref VARCHAR(512);

ALTER TABLE chunk_overrides
    ADD CONSTRAINT chunk_overrides_content_ref_ck CHECK (
        replacement_content_ref IS NULL OR (
            length(replacement_content_ref) BETWEEN 1 AND 512
            AND replacement_content_ref !~ '[[:space:]]'
            AND replacement_content_ref !~ '[[:cntrl:]]'
            AND replacement_content_ref !~* '(fulltext|full_text|rawtext|raw_text|rawdocument|raw_document|documentcontent|document_content|vector|embedding)'
        )
    );
