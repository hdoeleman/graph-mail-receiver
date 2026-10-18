package org.example.graphmail.store;

import org.springframework.data.repository.ListCrudRepository;

public interface SyncStateRepository extends ListCrudRepository<SyncState, String> {
}
