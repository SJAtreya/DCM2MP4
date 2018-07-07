import com.sun.media.imageio.stream.StreamSegment;
import com.sun.media.imageio.stream.StreamSegmentMapper;

/**
 * An implementation of the <code>StreamSegmentMapper</code> interface
 * that requires an explicit list of the starting locations and
 * lengths of the source segments.
 */
public class StreamSegmentMapperImpl implements StreamSegmentMapper {

    private long[] segmentPositions;

    private int[] segmentLengths;

    public StreamSegmentMapperImpl(long[] segmentPositions,
                                   int[] segmentLengths) {
        this.segmentPositions = (long[])segmentPositions.clone();
        this.segmentLengths = (int[])segmentLengths.clone();
    }

    public StreamSegment getStreamSegment(long position, int length) {
        int numSegments = segmentLengths.length;
        for (int i = 0; i < numSegments; i++) {
            int len = segmentLengths[i];
            if (position < len) {
                return new StreamSegment(segmentPositions[i] + position,
                                         Math.min(len - (int)position,
                                                  length));
            }
            position -= len;
        }

        return null;
    }

    public void getStreamSegment(long position, int length,
                                 StreamSegment seg) {
        int numSegments = segmentLengths.length;
        for (int i = 0; i < numSegments; i++) {
            int len = segmentLengths[i];
            if (position < len) {
                seg.setStartPos(segmentPositions[i] + position);
                seg.setSegmentLength(Math.min(len - (int)position, length));
                return;
            }
            position -= len;
        }

        seg.setStartPos(-1);
        seg.setSegmentLength(-1);
        return;
    }

    long length() {
        int numSegments = segmentLengths.length;
        long len = 0L;

        for(int i = 0; i < numSegments; i++) {
            len += segmentLengths[i];
        }

        return len;
    }
}

/**
 * An implementation of the <code>StreamSegmentMapper</code> interface
 * for segments of equal length.
 */
class SectorStreamSegmentMapper implements StreamSegmentMapper {

    long[] segmentPositions;
    int segmentLength;
    int totalLength;
    int lastSegmentLength;

    public SectorStreamSegmentMapper(long[] segmentPositions,
                                     int segmentLength,
                                     int totalLength) {
        this.segmentPositions = (long[])segmentPositions.clone();
        this.segmentLength = segmentLength;
        this.totalLength = totalLength;
        this.lastSegmentLength = totalLength -
            (segmentPositions.length - 1)*segmentLength;
    }

    public StreamSegment getStreamSegment(long position, int length) {
        int index = (int) (position/segmentLength);

        // Compute segment length
        int len = (index == segmentPositions.length - 1) ?
            lastSegmentLength : segmentLength;

        // Compute position within the segment
        position -= index*segmentLength;

        // Compute maximum legal length
        len -= position;
        if (len > length) {
            len = length;
        }
        return new StreamSegment(segmentPositions[index] + position, len);
    }

    public void getStreamSegment(long position, int length,
                                 StreamSegment seg) {
        int index = (int) (position/segmentLength);

        // Compute segment length
        int len = (index == segmentPositions.length - 1) ?
            lastSegmentLength : segmentLength;

        // Compute position within the segment
        position -= index*segmentLength;

        // Compute maximum legal length
        len -= position;
        if (len > length) {
            len = length;
        }

        seg.setStartPos(segmentPositions[index] + position);
        seg.setSegmentLength(len);
    }

    long length() {
        return (long)totalLength;
    }
}
