/**
 *
 */
package fr.cedrik.inotes.fs.maildir;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;

import fr.cedrik.inotes.MessageMetaData;

/**
 * @author C&eacute;drik LIME
 */
public class MailDir extends BaseMailDir {

	public MailDir() throws IOException {
		super();
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) throws IOException {
		new MailDir().run(args, null);
	}

	/**
	 * @param args
	 */
	@Override
	public void _main(String[] args) throws IOException {
		main(args);
	}

	@Override
	protected void help() {
		System.out.println("Usage: "+MailDir.class.getSimpleName()+" <out_file> [oldest message to fetch date: " + ISO8601_DATE_SEMITIME + ']');
	}

	@Override
	protected void writeMIME(Writer mbox, MessageMetaData message, Iterator<String> mime) throws IOException {
		while (mime.hasNext()) {
			String line = mime.next();
			mbox.append(line).append('\n');
		}
	}

}