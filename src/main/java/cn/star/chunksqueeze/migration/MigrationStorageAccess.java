package cn.star.chunksqueeze.migration;

import java.io.IOException;
import java.nio.file.Path;

public interface MigrationStorageAccess {
    Path chunksqueeze$migrationFolder();
    void chunksqueeze$closeRegionForMigration(int regionX, int regionZ) throws IOException;
}
