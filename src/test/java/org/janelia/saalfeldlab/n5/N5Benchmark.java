/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.n5;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ch.systemsx.cisd.base.mdarray.MDShortArray;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.HDF5IntStorageFeatures;
import ch.systemsx.cisd.hdf5.IHDF5ShortWriter;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ShortProcessor;
import net.imglib2.Cursor;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class N5Benchmark {

	private static String testDirPath = System.getProperty("user.home") + "/tmp/n5-benchmark";

	private static String datasetName = "/dataset";

	private static N5Writer n5;

	private static short[] data;

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {

		final File testDir = new File(testDirPath);
		testDir.mkdirs();
		if (!(testDir.exists() && testDir.isDirectory()))
			throw new IOException("Could not create benchmark directory for HDF5Utils benchmark.");

		data = new short[64 * 64 * 64];
		final ImagePlus imp = new Opener().openURL("https://imagej.nih.gov/ij/images/t1-head-raw.zip");
		final ImagePlusImg<UnsignedShortType, ?> img = (ImagePlusImg<UnsignedShortType, ?>)(Object)ImagePlusImgs.from(imp);
		final Cursor<UnsignedShortType> cursor = Views.flatIterable(Views.interval(img, new long[]{100, 100, 30}, new long[]{163, 163, 93})).cursor();
		for (int i = 0; i < data.length; ++i)
			data[i] = (short)cursor.next().get();

		n5 = N5.openFSWriter(testDirPath);
	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void rampDownAfterClass() throws Exception {

		n5.remove("");
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {}

	/**
	 * Generates some files for documentation of the binary format.  Not a test.
	 */
//	@Test
	public void testDocExample() {

		final short[] dataBlockData = new short[]{1, 2, 3, 4, 5, 6};
		for (final CompressionType compressionType : CompressionType.values()) {
			try {
				final String compressedDatasetName = datasetName + "." + compressionType.name();
				n5.createDataset(compressedDatasetName, new long[]{1, 2, 3}, new int[]{1, 2, 3}, DataType.UINT16, compressionType);
				final DatasetAttributes attributes = n5.getDatasetAttributes(compressedDatasetName);
				final ShortArrayDataBlock dataBlock = new ShortArrayDataBlock(new int[]{1, 2, 3}, new long[]{0, 0, 0}, dataBlockData);
				n5.writeBlock(compressedDatasetName, attributes, dataBlock);
			} catch (final IOException e) {
				fail(e.getMessage());
			}
		}
	}

//	@Test
	public void benchmarkWritingSpeed() {

		final int nBlocks = 5;

		for (int i = 0; i < 1; ++i) {
			for (final CompressionType compressionType : CompressionType.values()) {

				final long t = System.currentTimeMillis();
				try {
					final String compressedDatasetName = datasetName + "." + compressionType.name();
					n5.createDataset(compressedDatasetName, new long[]{64 * nBlocks, 64 * nBlocks, 64 * nBlocks}, new int[]{64, 64, 64}, DataType.UINT16, compressionType);
					final DatasetAttributes attributes = n5.getDatasetAttributes(compressedDatasetName);
					for (int z = 0; z < nBlocks; ++z)
						for (int y = 0; y < nBlocks; ++y)
							for (int x = 0; x < nBlocks; ++x) {
								final ShortArrayDataBlock dataBlock = new ShortArrayDataBlock(new int[]{64, 64, 64}, new long[]{x, y, z}, data);
								n5.writeBlock(compressedDatasetName, attributes, dataBlock);
							}
				} catch (final IOException e) {
					fail(e.getMessage());
				}
				System.out.println(String.format("%d : %s : %fs", i, compressionType.toString(), 0.001 * (System.currentTimeMillis() - t)));
			}

			/* TIF blocks */
			long t = System.currentTimeMillis();
			final String compressedDatasetName = testDirPath + "/" + datasetName + ".tif";
			new File(compressedDatasetName).mkdirs();
			for (int z = 0; z < nBlocks; ++z)
				for (int y = 0; y < nBlocks; ++y)
					for (int x = 0; x < nBlocks; ++x) {
						final ImagePlus impBlock = new ImagePlus("", new ShortProcessor(64, 64 * 64, data, null));
						IJ.saveAsTiff(impBlock, compressedDatasetName + "/" + x + "-" + y + "-" + z + ".tif");
					}
			System.out.println(String.format("%d : tif : %fs", i, 0.001 * (System.currentTimeMillis() - t)));

			/* HDF5 raw */
			t = System.currentTimeMillis();
			String hdf5Name = testDirPath + "/" + datasetName + ".h5";
			IHDF5Writer hdf5Writer = HDF5Factory.open(hdf5Name);
			IHDF5ShortWriter uint16Writer = hdf5Writer.uint16();
			uint16Writer.createMDArray(
					datasetName,
					new long[]{64 * nBlocks, 64 * nBlocks, 64 * nBlocks},
					new int[]{64, 64, 64},
					HDF5IntStorageFeatures.INT_NO_COMPRESSION);
			for (int z = 0; z < nBlocks; ++z)
				for (int y = 0; y < nBlocks; ++y)
					for (int x = 0; x < nBlocks; ++x) {
						final MDShortArray targetCell = new MDShortArray(data, new int[]{64, 64, 64});
						uint16Writer.writeMDArrayBlockWithOffset(datasetName, targetCell, new long[]{64 * z, 64 * y, 64 * x});
					}
			System.out.println(String.format("%d : hdf5 raw : %fs", i, 0.001 * (System.currentTimeMillis() - t)));
			new File(hdf5Name).delete();

			/* HDF5 gzip */
			t = System.currentTimeMillis();
			hdf5Name = testDirPath + "/" + datasetName + ".gz.h5";
			hdf5Writer = HDF5Factory.open(hdf5Name);
			uint16Writer = hdf5Writer.uint16();
			uint16Writer.createMDArray(
					datasetName,
					new long[]{64 * nBlocks, 64 * nBlocks, 64 * nBlocks},
					new int[]{64, 64, 64},
					HDF5IntStorageFeatures.INT_AUTO_SCALING_DEFLATE);
			for (int z = 0; z < nBlocks; ++z)
				for (int y = 0; y < nBlocks; ++y)
					for (int x = 0; x < nBlocks; ++x) {
						final MDShortArray targetCell = new MDShortArray(data, new int[]{64, 64, 64});
						uint16Writer.writeMDArrayBlockWithOffset(datasetName, targetCell, new long[]{64 * z, 64 * y, 64 * x});
					}
			System.out.println(String.format("%d : hdf5 gzip : %fs", i, 0.001 * (System.currentTimeMillis() - t)));
			new File(hdf5Name).delete();
		}
	}

	@Test
	public void benchmarkParallelWritingSpeed() {

		final int nBlocks = 5;

		for (int i = 1; i <= 16; i *= 2 ) {

			System.out.println( i + " threads.");

			final ExecutorService exec = Executors.newFixedThreadPool(i);
			final ArrayList<Future<Boolean>> futures = new ArrayList<>();
			long t;

			for (final CompressionType compressionType : CompressionType.values()) {
				t = System.currentTimeMillis();
				try {
					final String compressedDatasetName = datasetName + "." + compressionType.name();
					n5.createDataset(compressedDatasetName, new long[]{64 * nBlocks, 64 * nBlocks, 64 * nBlocks}, new int[]{64, 64, 64}, DataType.UINT16, compressionType);
					final DatasetAttributes attributes = n5.getDatasetAttributes(compressedDatasetName);
					for (int z = 0; z < nBlocks; ++z) {
						final int fz = z;
						for (int y = 0; y < nBlocks; ++y) {
							final int fy = y;
							for (int x = 0; x < nBlocks; ++x) {
								final int fx = x;
								futures.add(
										exec.submit(
												() -> {
													final ShortArrayDataBlock dataBlock = new ShortArrayDataBlock(new int[]{64, 64, 64}, new long[]{fx, fy, fz}, data);
													n5.writeBlock(compressedDatasetName, attributes, dataBlock);
													return true;
												}));
							}
						}
					}
					for (final Future<Boolean> f : futures)
						f.get();

					System.out.println(String.format("%d : %s : %fs", i, compressionType.toString(), 0.001 * (System.currentTimeMillis() - t)));
				} catch (final IOException | InterruptedException | ExecutionException e) {
					fail(e.getMessage());
				}
			}

			/* TIF blocks */
			futures.clear();
			t = System.currentTimeMillis();
			final String compressedDatasetName = testDirPath + "/" + datasetName + ".tif";
			try {
				new File(compressedDatasetName).mkdirs();
				for (int z = 0; z < nBlocks; ++z) {
					final int fz = z;
					for (int y = 0; y < nBlocks; ++y) {
						final int fy = y;
						for (int x = 0; x < nBlocks; ++x) {
							final int fx = x;
							futures.add(
									exec.submit(
											() -> {
												final ImagePlus impBlock = new ImagePlus("", new ShortProcessor(64, 64 * 64, data, null));
												IJ.saveAsTiff(impBlock, compressedDatasetName + "/" + fx + "-" + fy + "-" + fz + ".tif");
												return true;
											}));
						}
					}
				}
				for (final Future<Boolean> f : futures)
					f.get();

				System.out.println(String.format("%d : tif : %fs", i, 0.001 * (System.currentTimeMillis() - t)));
			} catch (final InterruptedException | ExecutionException e) {
				fail(e.getMessage());
			}

			/* HDF5 raw */
			futures.clear();
			t = System.currentTimeMillis();
			try {
				final String hdf5Name = testDirPath + "/" + datasetName + ".h5";
				final IHDF5Writer hdf5Writer = HDF5Factory.open( hdf5Name );
				final IHDF5ShortWriter uint16Writer = hdf5Writer.uint16();
				uint16Writer.createMDArray(
						datasetName,
						new long[]{64 * nBlocks, 64 * nBlocks, 64 * nBlocks},
						new int[]{64, 64, 64},
						HDF5IntStorageFeatures.INT_NO_COMPRESSION);
				for (int z = 0; z < nBlocks; ++z) {
					final int fz = z;
					for (int y = 0; y < nBlocks; ++y) {
						final int fy = y;
						for (int x = 0; x < nBlocks; ++x) {
							final int fx = x;
							futures.add(
									exec.submit(
											() -> {
												final MDShortArray targetCell = new MDShortArray(data, new int[]{64, 64, 64});
												uint16Writer.writeMDArrayBlockWithOffset(datasetName, targetCell, new long[]{64 * fz, 64 * fy, 64 * fx});
												return true;
											}));
						}
					}
				}
				for (final Future<Boolean> f : futures)
					f.get();

				hdf5Writer.close();
				System.out.println(String.format("%d : hdf5 raw : %fs", i, 0.001 * (System.currentTimeMillis() - t)));
				new File(hdf5Name).delete();
			} catch (final InterruptedException | ExecutionException e) {
				fail(e.getMessage());
			}

			/* HDF5 gzip */
			futures.clear();
			t = System.currentTimeMillis();
			try {
				final String hdf5Name = testDirPath + "/" + datasetName + ".gz.h5";
				final IHDF5Writer hdf5Writer = HDF5Factory.open( hdf5Name );
				final IHDF5ShortWriter uint16Writer = hdf5Writer.uint16();
				uint16Writer.createMDArray(
						datasetName,
						new long[]{64 * nBlocks, 64 * nBlocks, 64 * nBlocks},
						new int[]{64, 64, 64},
						HDF5IntStorageFeatures.INT_AUTO_SCALING_DEFLATE);
				for (int z = 0; z < nBlocks; ++z) {
					final int fz = z;
					for (int y = 0; y < nBlocks; ++y) {
						final int fy = y;
						for (int x = 0; x < nBlocks; ++x) {
							final int fx = x;
							futures.add(
									exec.submit(
											() -> {
												final MDShortArray targetCell = new MDShortArray(data, new int[]{64, 64, 64});
												uint16Writer.writeMDArrayBlockWithOffset(datasetName, targetCell, new long[]{64 * fz, 64 * fy, 64 * fx});
												return true;
											}));
						}
					}
				}
				for (final Future<Boolean> f : futures)
					f.get();

				hdf5Writer.close();
				System.out.println(String.format("%d : hdf5 gzip : %fs", i, 0.001 * (System.currentTimeMillis() - t)));
				new File(hdf5Name).delete();
			} catch (final InterruptedException | ExecutionException e) {
				fail(e.getMessage());
			}

			exec.shutdown();
		}
	}
}
