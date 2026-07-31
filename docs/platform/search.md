# Search API — moved

> **⚠ Superseded.** The search documentation now lives in the dedicated
> [`docs/search/`](../search/README.md) directory, which covers the full
> subsystem — this page previously documented only the unified endpoint
> and the research reindex.

Where things went:

| You were looking for | Now at |
|---|---|
| `GET /api/v1/search` (unified search — now **8 entity types**: POST, REEL, QUESTION, ANSWER, RESEARCH, USER, CHANNEL, SOUND) | [../search/global-search.md](../search/global-search.md) |
| Ranking model (BM25 layers, boosts, decay) | [../search/global-search.md](../search/global-search.md) + [../search/algorithms-and-complexity.md](../search/algorithms-and-complexity.md) |
| Admin reindex (`/api/v1/admin/search/*/reindex` — now **7 corpora**: posts, questions, answers, research, users, channels, sounds) | [../search/indexing-and-reindex.md](../search/indexing-and-reindex.md) |
| Indexing pipeline / eventual consistency | [../search/indexing-and-reindex.md](../search/indexing-and-reindex.md) |
| What's searchable at all | [../search/coverage.md](../search/coverage.md) |

The division of labour with the tag subsystem is unchanged: relevance
search is Elasticsearch's job; trending / tag feeds are usage-based
Cassandra counters — see [tags.md](./tags.md).
