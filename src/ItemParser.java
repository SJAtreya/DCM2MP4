

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

import javax.imageio.stream.ImageInputStream;

import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.util.TagUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.media.imageio.stream.SegmentedImageInputStream;
import com.sun.media.imageio.stream.StreamSegment;
import com.sun.media.imageio.stream.StreamSegmentMapper;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @version $Revision$ $Date$
 * @since Aug 6, 2007
 */
public class ItemParser implements StreamSegmentMapper {

    private static final Logger log = LoggerFactory.getLogger(ItemParser.class);

    private static final HashSet<String> JPEG_TS = new HashSet<String>(
            Arrays.asList(new String[] { UID.JPEGBaseline1, UID.JPEGExtended24,
                    UID.JPEGExtended35Retired,
                    UID.JPEGSpectralSelectionNonHierarchical68Retired,
                    UID.JPEGSpectralSelectionNonHierarchical79Retired,
                    UID.JPEGFullProgressionNonHierarchical1012Retired,
                    UID.JPEGFullProgressionNonHierarchical1113Retired,
                    UID.JPEGLosslessNonHierarchical14,
                    UID.JPEGLosslessNonHierarchical15Retired,
                    UID.JPEGExtendedHierarchical1618Retired,
                    UID.JPEGExtendedHierarchical1719Retired,
                    UID.JPEGSpectralSelectionHierarchical2022Retired,
                    UID.JPEGSpectralSelectionHierarchical2123Retired,
                    UID.JPEGFullProgressionHierarchical2426Retired,
                    UID.JPEGFullProgressionHierarchical2527Retired,
                    UID.JPEGLosslessHierarchical28Retired,
                    UID.JPEGLosslessHierarchical29Retired, UID.JPEGLossless,
                    UID.JPEGLSLossless, UID.JPEGLSLossyNearLossless,
                    UID.JPEG2000LosslessOnly, UID.JPEG2000 }));
    
    private static final HashSet<String> VIDEO_TS = new HashSet<String>(
        Arrays.asList(new String[] { UID.MPEG2, UID.MPEG2MainProfileHighLevel,
        		UID.MPEG4AVCH264BDCompatibleHighProfileLevel41, 
        		UID.MPEG4AVCH264HighProfileLevel41 }));

    public static final class Item {

        public final int offset;

        public final long startPos;

        public final int length;
        
        public Item(int offset, long startPos, int length) {
            this.offset = offset;
            this.startPos = startPos;
            this.length = length;
        }

        final int nextOffset() {
            return offset + length;
        }

        final long nextItemPos() {
            return startPos + length;
        }

        @Override
        public String toString() {
            return "Item[off=" + offset + ", pos=" + startPos + ", len="
                    + length + "]";
        }
    }

    private final ArrayList<Item> items = new ArrayList<Item>();

    private final DicomInputStream dis;

    private final ImageInputStream iis;

    private final ArrayList<Item> firstItemOfFrame;

    private final int numberOfFrames;

    private final boolean rle;

    private final boolean jpeg;

    private long[] basicOffsetTable;

    private final byte[] soi = new byte[2];

    private boolean lastItemSeen = false;

    private int frame;

    public ItemParser(DicomInputStream dis, ImageInputStream iis,
            int numberOfFrames, String tsuid) throws IOException {
        this.dis = dis;
        this.iis = iis;
        // Handle video type data - eventually there should be another way to compute this
        if( VIDEO_TS.contains(tsuid) ) numberOfFrames = 1;
        this.numberOfFrames = numberOfFrames; 
        this.firstItemOfFrame = new ArrayList<Item>(numberOfFrames);
        this.rle = UID.RLELossless.equals(tsuid);
        this.jpeg = !rle && JPEG_TS.contains(tsuid);
        dis.readHeader();
        int offsetTableLen = dis.length();
        if (offsetTableLen != 0) {
            if (offsetTableLen != numberOfFrames * 4) {

                if(isJPEG(iis)) {
                    log.debug("JPEG image is in the offset table sequence slot.");

                    // TODO: Make this a more general solution.
                    Item item = new Item(0,
                            iis.getStreamPosition(),
                            dis.length());
                    this.items.add(item);
                    firstItemOfFrame.add(item);
                    this.lastItemSeen = true;
                }
                else {
                    log.warn("Skip Basic Offset Table with illegal length: {} for image with {} frames!",offsetTableLen,numberOfFrames);
                    iis.skipBytes(offsetTableLen);
                }


            } else {
                basicOffsetTable = new long[numberOfFrames];
                long highWord = 0, highInc = 0x100000000L;
                iis.setByteOrder(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < basicOffsetTable.length; i++) {
                    basicOffsetTable[i] = highWord | (0xFFFFFFFF & iis.readInt());
                    // Allow wrapping of the counter in case we get more than 2^32 sized offsets.
                    if( i>0 && basicOffsetTable[i] < basicOffsetTable[i-1] ) {
                    	highWord += highInc;
                    	basicOffsetTable[i] += highInc;
                    }
                    
                }
            }
        }
        next();
        
        // Create the items/first items based on the basic offset table.
        if( basicOffsetTable!=null ) {
            Item firstItem = firstItemOfFrame.get(0);
            Item prev = firstItemOfFrame.get(firstItemOfFrame.size()-1);
            for(int i=firstItemOfFrame.size(); i<basicOffsetTable.length-1; i++) {
        		Item addItem = new Item(prev.nextOffset(), firstItem.startPos + basicOffsetTable[i],
                        (int) (basicOffsetTable[i+1]-basicOffsetTable[i]-8));
        		addFirstItemOfFrame(addItem);
        		items.add(addItem);
        		prev = addItem;
        	}
        }        
    }

    /**
     * Sniff the next set of bytes in the DicomInputStream and dettermine if they represent a JPEG image.
     */
    private boolean isJPEG(ImageInputStream iis) throws IOException {

        byte[] firstBytes = new byte[10];
        iis.mark();
        iis.read(firstBytes);
        iis.reset();


        boolean isJPEG = JPEGVerifier.isJPEGMagicBytes(firstBytes);
        boolean isJPEG2k = JPEGVerifier.isJPEG2KMagicBytes(firstBytes);
        return isJPEG || isJPEG2k;
    }

    public int getNumberOfDataFragments() {
        while (!lastItemSeen)
            next();
        return items.size();
    }

    private Item getFirstItemOfFrame(int frame) throws IOException {
        while (firstItemOfFrame.size() <= frame) {
            if (next() == null)
                throw new IOException("Could not detect first item of frame #"
                        + (frame+1));
        }
        return firstItemOfFrame.get(frame);
    }

    private Item next() {
        if (lastItemSeen)
            return null;
        try {
            if (!items.isEmpty())
                iis.seek(last().nextItemPos());
            dis.readHeader();
            if (log.isDebugEnabled())
                log.debug("Read " + TagUtils.toString(dis.tag()) + " #"
                        + dis.length());
            if (dis.tag() == Tag.Item) {
                Item item = new Item(
                        items.isEmpty() ? 0 : last().nextOffset(),
                        iis.getStreamPosition(),
                        dis.length());
                if (items.isEmpty() || rle) {
                    addFirstItemOfFrame(item);
                } else if (firstItemOfFrame.size() < numberOfFrames) {
                    if (basicOffsetTable != null) {
                        Item firstItem = firstItemOfFrame.get(0);
                        int frame = firstItemOfFrame.size();
                        if (item.startPos == firstItem.startPos + basicOffsetTable[frame]) {
                            if (log.isDebugEnabled()) {
                                log.debug("Start position of item #"
                                        + (items.size()+1) + " matches "
                                        + (frame+1)
                                        + ".entry of Basic Offset Table.");
                            }
                            addFirstItemOfFrame(item);
                        }
                    } else if (jpeg) {
                        iis.read(soi, 0, 2);
                        if (soi[0] == (byte) 0xFF && (soi[1] == (byte) 0xD8
                                                   || soi[1] == (byte) 0x4F)) {
                            if (log.isDebugEnabled()) {
                                log.debug("Detect JPEG SOI/SOC at item #"
                                        + (items.size()+1));
                            }
                            addFirstItemOfFrame(item);
                        }
                        iis.seek(item.startPos);
                    }
                }
                items.add(item);
                return item;
            }
        } catch (IOException e) {
            log.warn("i/o error reading next item:", e);
        }
        if (dis.tag() != Tag.SequenceDelimitationItem
                || dis.length() != 0) {
            log.warn("expected (FFFE,E0DD) #0 but read "
                    + TagUtils.toString(dis.tag()) + " #" + dis.length());
        }
        lastItemSeen = true;
        return null;
    }

    private void addFirstItemOfFrame(Item item) {
        if (log.isDebugEnabled()) {
            log.debug("Detect item #" + (items.size()+1)
                    + " as first item of frame #"
                    + (firstItemOfFrame.size()+1));
        }
        firstItemOfFrame.add(item);
    }

    private Item last() {
        return items.get(items.size() - 1);
    }

    public StreamSegment getStreamSegment(long pos, int len) {
        StreamSegment retval = new StreamSegment();
        getStreamSegment(pos, len, retval);
        return retval;
    }
    
    protected int findItemPosition(long offset) {
    	int s=0, e=items.size()-1;
    	int m = (s+e)/2;
    	while( e>s ) {
    		Item test = items.get(m);
    		if( test.offset==offset ) return m;
    		if( test.offset < offset ) s = m;
    		else e = m-1;
    		m = (s+e+1)/2;
    	}
    	return m;
    }
    protected int findItemPosition(Item item) {
    	return findItemPosition(item.offset);
    }

    public void getStreamSegment(long pos, int len, StreamSegment seg) {
        if (log.isDebugEnabled())
            log.debug("getStreamSegment(pos=" + pos + ", len=" + len + ")");
        if (isEndOfFrame(pos)) {
            setEOF(seg);
            return;
        }
        Item item = items.get(findItemPosition(pos));
        seg.setStartPos(item.startPos + pos - item.offset);
        seg.setSegmentLength(Math.min((int) (item.offset + item.length - pos),
                len));
        if( seg.getSegmentLength() == 0 ) {
            setEOF(seg);
        }
        if (log.isDebugEnabled())
            log.debug("return StreamSegment[start=" + seg.getStartPos()
                    + ", len=" + seg.getSegmentLength() + "]");
    }

    private boolean isEndOfFrame(long pos) {
    	if( frame+1 < firstItemOfFrame.size() ) {
            return firstItemOfFrame.get(frame+1).offset <= pos;
    	}
    	if( numberOfFrames>1 ) {
    		// Going through the items in multiframe doesn't really work correctly, so avoid it.
    		return false;
    	}
    	for(Item i : items) {
    		pos -= i.length;
    		if( pos<0 ) return false;
    	}
    	if( lastItemSeen ) return true;
    	next();
    	return false;
    }

    private void setEOF(StreamSegment seg) {
        seg.setSegmentLength(-1);
        if (log.isDebugEnabled())
            log.debug("return StreamSegment[start=" + seg.getStartPos()
                    + ", len=-1]");
    }

    public void seekFrame(SegmentedImageInputStream siis, int frame)
            throws IOException {
        if (log.isDebugEnabled())
            log.debug("seek frame #" + (frame+1));
        Item item = getFirstItemOfFrame(frame);
        siis.seek(item.offset);
        iis.seek(item.startPos);
        this.frame = frame;
        if (log.isDebugEnabled())
            log.debug("seek " + item);
    }

    public byte[] readFrame(SegmentedImageInputStream siis, int frame)
            throws IOException {
        int frameSize = getFrameLength(frame);
        byte[] data = new byte[frameSize];
        seekFrame(siis, frame);
        siis.readFully(data);
        return data;
    }

    /**
     * Gets the frame length for the given frame - note that the segmented frame size is incorrect, in general, so use this method instead.
     * @param frame
     * @return length of the frame.
     * @throws IOException
     */
	public int getFrameLength(int frame) throws IOException {
		Item item = getFirstItemOfFrame(frame);
        int frameSize = item.length;
        int firstItemOfNextFrameIndex = frame + 1 < numberOfFrames
                ? items.indexOf(getFirstItemOfFrame(frame + 1))
                : getNumberOfDataFragments();
        for (int i = findItemPosition(item.offset) + 1; i < firstItemOfNextFrameIndex; i++) {
            frameSize += items.get(i).length;
        }
		return frameSize;
	}   

    /**
     * Fetch the offset and  length of the provided frame
     *  
     * @param frame
     * @return
     * @throws IOException
     */
    public long[] fetchFrameOffsetAndLength(int frame)
    throws IOException {
    	long offsetLength[] = new long [2];
        int frameSize = getFrameLength(frame);
        Item item = getFirstItemOfFrame(frame);
        offsetLength [0] = item.startPos;
        offsetLength [1] = frameSize;
        return offsetLength;
    }	
	
    /**
     * seekFrame to right frame in order for ImageInputStream to read 
     * @param siis
     * @param frame
     * @return
     * @throws IOException
     */
    public int seekImageFrameBeforeReadStream(SegmentedImageInputStream siis, int frame)
    throws IOException {
        int frameSize = getFrameLength(frame);
        seekFrame(siis, frame);
        return frameSize;
    }

    public void seekFooter() throws IOException {
        iis.seek(last().nextItemPos());
        dis.readHeader();
    }

    /**
     * Read in the offset and length pairs for each fragment of the a single
     * frame image into the returned array.
     */
	public long[] fetchImageOffsetAndLengths() {
		int numberOfFragments = this.getNumberOfDataFragments();
		
		long[] offsetAndLengths = new long[numberOfFragments*2];
		for(int i=0;i<numberOfFragments;i++) {
			Item item = this.items.get(i);
			offsetAndLengths[i*2] = item.startPos;
			offsetAndLengths[i*2+1] = item.length;
		}
		
		return offsetAndLengths;
	}
}

class JPEGVerifier {


    public static final byte[] JPEG_MAGIC_BYTES = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    public static final byte[] JPEG_2_K_MAGIC_BYTES = new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x0C, (byte) 0x6A, (byte) 0x50, (byte) 0x20, (byte) 0x20, (byte) 0x0D, (byte) 0x0A};


    public static boolean isJPEGMagicBytes(byte[] firstBytesOfImage) {
        return containsBytes(firstBytesOfImage,JPEG_MAGIC_BYTES);
    }

    public static boolean isJPEG2KMagicBytes(byte[] firstBytesOfImage) {
        return containsBytes(firstBytesOfImage,JPEG_2_K_MAGIC_BYTES);
    }

    /**
     * Search the parent byte array for a pattern that matches the sub set that is passed.
     */
    public static boolean containsBytes(byte[] parent, byte[] sub){
    	if(parent.length == 0 || sub.length == 0) return false;

    	boolean in = false;

    	for(int i=0; i<parent.length; i++){
    		if(parent[i] == sub[0]){
				in = true;
    			for(int j=1; j<sub.length; j++){
    				if(sub[j] != parent[i+j]){
    					in = false;
    					break;
    				}
    			}
    			if(in)
    				break;
    		}
    	}
    	return in;
    }
}