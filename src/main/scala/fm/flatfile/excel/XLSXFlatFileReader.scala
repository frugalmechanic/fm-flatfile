/*
 * Copyright 2014 Frugal Mechanic (http://frugalmechanic.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fm.flatfile.excel

import fm.flatfile.{FlatFileParsedRow, FlatFileReaderOptions}
import fm.common.Implicits._
import fm.lazyseq.LazySeq
import java.io.{BufferedInputStream, InputStream}
import scala.util.Try

object XLSXFlatFileReader extends ExcelFlatFileReader {
  def makeLineReader(in: InputStream, options: FlatFileReaderOptions): LazySeq[Try[FlatFileParsedRow]] = {
    val bis: BufferedInputStream = in.toBufferedInputStream
    if (!ExcelFlatFileReader.isXLSXFormat(bis)) throw ExcelFlatFileReaderException.InvalidExcelFile("Not an XLSX File")
    new XLSXStreamReaderImpl(bis, options)
  }
}