import java.io.IOException;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.io.DicomInputHandler;
import org.dcm4che3.io.DicomInputStream;

/**
 * @author gunter zeilinger(gunterze@gmail.com)
 * @version $Revision: 420 $ $Date: 2005-10-05 21:40:23 +0200 (Wed, 05 Oct 2005)
 *          $
 * @since Jun 26, 2005
 *
 */
public class StopTagInputHandler implements DicomInputHandler {

	private final long stopTag;

	public StopTagInputHandler(int stopTag) {
		this.stopTag = stopTag & 0xffffffffL;
	}

	@Override
	public void endDataset(DicomInputStream arg0) throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void readValue(DicomInputStream in, Attributes arg1) throws IOException {
		// TODO Auto-generated method stub
		if ((in.tag() & 0xffffffffL) >= stopTag && in.level() == 0)
			return;
		in.readValue(in, arg1);
	}

	@Override
	public void readValue(DicomInputStream arg0, Sequence arg1) throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void readValue(DicomInputStream arg0, Fragments arg1) throws IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void startDataset(DicomInputStream arg0) throws IOException {
		// TODO Auto-generated method stub

	}

}