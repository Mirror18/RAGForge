# Permission Fixture

The query context must contain `space_id=space-alpha` before tenant content is read.

Missing or mismatched `space_id` is a deny condition, not a fallback to a broader search.
