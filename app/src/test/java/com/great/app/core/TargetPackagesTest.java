package com.great.app.core;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;
public final class TargetPackagesTest {
    @Test public void limitAndRemoval() {
        TargetPackages targets = new TargetPackages(Collections.emptyList());
        for(int i=0;i<15;i++) targets.add("com.example.app"+i);
        try { targets.add("com.example.extra"); fail(); } catch(IllegalArgumentException expected) { }
        targets.remove("com.example.app0"); targets.add("com.example.extra");
        assertEquals(15,targets.names().size());
    }
    @Test public void validatesNamesAndDuplicates() {
        TargetPackages targets = new TargetPackages(Collections.emptyList());
        targets.add(" com.android.chrome ");
        for(String invalid : new String[]{"", "Chrome", "com..app", "com.app bad", "com.android.chrome"}) {
            try { targets.add(invalid); fail(invalid); } catch(IllegalArgumentException expected) { }
        }
        assertEquals(Collections.singletonList("com.android.chrome"),targets.names());
        assertEquals(targets.names(),new TargetPackages(targets.names()).names());
    }
}
