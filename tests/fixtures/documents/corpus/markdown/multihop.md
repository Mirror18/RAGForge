# Multi-hop Evidence

The **Atlas** pipeline uses the `north` region defined in the region table.
The chunker code fixture sets its chunk size to 512 tokens.

To answer a multi-hop question, join the region identity with the parser configuration and cite both source documents.
