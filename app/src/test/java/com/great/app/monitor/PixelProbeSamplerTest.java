package com.great.app.monitor;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PixelProbeSamplerTest {
    private static void pixel(ByteBuffer b, int base, int stride, int row, int x, int y, int rgb) {
        int o = base + row*y + stride*x;
        b.put(o,(byte)(rgb>>16)); b.put(o+1,(byte)(rgb>>8)); b.put(o+2,(byte)rgb);
    }
    @Test public void samplesCrossInOrderWithPaddingAndNonzeroPosition() {
        ByteBuffer b = ByteBuffer.allocate(200); b.position(7);
        int[][] points = {{2,2},{1,2},{3,2},{2,1},{2,3}};
        for (int i=0;i<5;i++) pixel(b,7,4,32,points[i][0],points[i][1],(i+1)*0x111111);
        PixelSample s = new PixelProbeSampler().sample(b,4,32,0,0,5,5,2,2,1.25f,1.25f);
        assertEquals(5,s.count);
        for(int i=0;i<5;i++) assertEquals((i+1)*0x111111,s.probe(i));
        assertEquals(7,b.position());
    }
    @Test public void tinyRadiusUsesOnlyCentreAndPlanChangesWithRadius() {
        ByteBuffer b=ByteBuffer.allocate(5*5*4);
        PixelProbeSampler sampler = new PixelProbeSampler();
        assertEquals(1,sampler.sample(b,4,20,0,0,5,5,2,2,.5f,.5f).count);
        assertEquals(3,sampler.sample(b,4,20,0,0,5,5,2,2,1f,.5f).count);
        assertEquals(5,sampler.sample(b,4,20,0,0,5,5,2,2,1f,1f).count);
    }
    @Test public void cropEdgesSkipOutsideProbesWithoutClampingDuplicates() {
        ByteBuffer b=ByteBuffer.allocate(5*5*4);
        PixelSample s=new PixelProbeSampler().sample(b,4,20,2,2,5,5,2,2,1,1);
        assertEquals(3,s.count);
    }
    @Test public void malformedPlanesAndOutOfCropAreRejected() {
        PixelProbeSampler p=new PixelProbeSampler(); ByteBuffer b=ByteBuffer.allocate(2);
        assertNull(p.sample(b,4,8,0,0,2,2,0,0,.5f,.5f));
        assertNull(p.sample(b,2,8,0,0,2,2,0,0,.5f,.5f));
        assertNull(p.sample(b,4,8,1,1,2,2,0,0,.5f,.5f));
    }
    @Test public void geometryPreservesCropOffsetRoundingAndHalfScale() {
        assertEquals(280,MonitorGeometry.center(540,1080,10,550));
        assertEquals(510,MonitorGeometry.center(1000,2000,10,1010));
        assertEquals(549,MonitorGeometry.center(1080,1080,10,550));
        assertEquals(1.25f,MonitorGeometry.radius(5,1080,540),0);
        assertEquals(.5f,MonitorGeometry.radius(1,1080,540),0);
    }
    @Test public void physicalDiameterHasVerifiedDpiFallback() {
        assertEquals(5,MonitorGeometry.mmToPx(.3f,420,420,420));
        assertEquals(5,MonitorGeometry.mmToPx(.3f,Float.NaN,Float.POSITIVE_INFINITY,420));
        assertEquals(5,MonitorGeometry.mmToPx(.3f,99,1001,420));
    }
}
