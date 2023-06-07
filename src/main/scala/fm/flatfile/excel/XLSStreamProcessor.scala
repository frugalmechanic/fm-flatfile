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

import java.io.InputStream
import fm.flatfile.{FlatFileParsedRow, FlatFileReaderOptions}
import fm.common.Logging
import org.apache.poi.hssf.eventusermodel._
import org.apache.poi.hssf.eventusermodel.dummyrecord._
import org.apache.poi.ss.formula.eval.ErrorEval
import org.apache.poi.hssf.record._
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.hssf.usermodel.HSSFDataFormat
import org.apache.poi.ss.usermodel.CellType
import scala.util.{Success, Try}

private[excel] final class XLSStreamProcessor[T](is: InputStream, options: FlatFileReaderOptions, f: Try[FlatFileParsedRow] => T) extends HSSFListener with Logging {
  private[this] val fs = new POIFSFileSystem(is)
  private[this] val listener = new MissingRecordAwareHSSFListener(this)

  private[this] var lastRowNumber: Int = 0
  private[this] var lastColumnNumber: Int = 0

  // For parsing Formulas
  //private[this] var workbookBuildingListener: SheetRecordCollectingListener = _
  //private[this] var stubWorkbook: HSSFWorkbook = null

  // Records we pick up as we process
  private[this] var sstRecord: SSTRecord = null
  private[this] val formatListener: FormatTrackingHSSFListener = new FormatTrackingHSSFListener(listener)
  
  // So we known which sheet we're on
  private[this] var sheetIndex: Int = -1
  
  // As we see sheet names we add them here
  private[this] var sheetNames: Vector[String] = Vector.empty
  
  // By default we target the first sheet unless there is an options.sheetName
  // defined then this value we be set when we find the matching sheet name
  private[this] var targetSheetIndex: Int = if (null == options.sheetName) 0 else Int.MinValue

  // For handling formulas with string results
  private[this] var nextRow: Int = 0
  private[this] var nextColumn: Int = 0
  private[this] var outputNextStringRecord: Boolean = _
  
  private[this] var rowBuilder = Vector.newBuilder[String]
  //private[this] var minColumns: Int = 0 // based upon xssfHeaders.size - ensure any column that has a header has at least a blank value

  private def formatBoolean(bool: Boolean): String = {
    bool match {
      case true => "TRUE"
      case false => "FALSE"
    }
  }

  def process(): Unit = {
    val factory: HSSFEventFactory = new HSSFEventFactory()

    val request: HSSFRequest = new HSSFRequest()
    request.addListenerForAllRecords(formatListener)

    factory.processWorkbookEvents(request, fs)
  }
  
  private case class Column(row: Int, column: Int, value: String)
  
  /**
   * Main HSSFListener method, processes events, and outputs the
   *  CSV as the file is processed.
   */
  override def processRecord(record: Record): Unit = {
    
    // Control Records that we always process
    record match {
      // These are all at the beginning of the file and defines the sheet names
      case bsr: BoundSheetRecord =>
        val sheetName: String = bsr.getSheetname
        
        sheetNames = sheetNames :+ sheetName
        
        if (null != options.sheetName && options.sheetName.equalsIgnoreCase(sheetName)) {
          targetSheetIndex = sheetNames.size - 1
        }
        
        return // Nothing else to do for this record so just return
        
      case br: BOFRecord =>
        if (br.getType() == BOFRecord.TYPE_WORKSHEET) {
          sheetIndex += 1
        }
        
        return // Nothing else to do for this record so just return
      
      // Static String Table Record
      case sstr: SSTRecord =>
        sstRecord = sstr
        return // Nothing else to do for this record so just return
        
      case _ => // Ignore other records
    }

    val isTargetSheet: Boolean = targetSheetIndex == sheetIndex
    
    // Skip processing the records if this is not our target sheet
    if (!isTargetSheet) return

    val column: Option[Column] = record match {
      case brec: BlankRecord =>
        Some(Column(brec.getRow(), brec.getColumn, ""))
      
      case berec: BoolErrRecord =>
        Some(Column(berec.getRow(), berec.getColumn(), formatBoolean(berec.getBooleanValue())))
      
      case frec: FormulaRecord =>
        if (frec.hasCachedResultString()) {
          // Formula result is a string
          // This is stored in the next StringRecord record
          outputNextStringRecord = true
          nextRow = frec.getRow()
          nextColumn = frec.getColumn()
          None
        } else {
          val formulaString: String = frec.getCachedResultTypeEnum match {
            case CellType.NUMERIC => formatListener.formatNumberDateCell(frec)
            case CellType.STRING => formatListener.formatNumberDateCell(frec)
            case CellType.BOOLEAN => frec.getCachedBooleanValue() match {
              case false => "FALSE"
              case true => "TRUE"
            }
            case CellType.ERROR => ErrorEval.getText(frec.getCachedErrorValue())
            case _ => logger.warn("Unknown Formula Result Type"); "<unknown>"
          }
          Some(Column(frec.getRow, frec.getColumn(), formulaString))
        }
      
      case srec: StringRecord =>
        // String for formula
        if (outputNextStringRecord) {
          outputNextStringRecord = false
          Some(Column(nextRow, nextColumn, srec.getString()))
        } else None
      
      case lrec: LabelRecord =>
        Some(Column(lrec.getRow, lrec.getColumn, lrec.getValue))
      
      case lsrec: LabelSSTRecord =>  
        val sstString = if (sstRecord == null) {
          logger.warn(s"Missing SST Record for LabelSSTRecord: $lsrec")
          ""
        } else {
          sstRecord.getString(lsrec.getSSTIndex()).toString()
        }
        Some(Column(lsrec.getRow, lsrec.getColumn, sstString))
      
      case nrec: NoteRecord =>
        // TODO: Find object to match nrec.getShapeId()
        logger.trace("Unsupported Cell Type: NoteRecord")
        Some(Column(nrec.getRow, nrec.getColumn, ""))
      
      case numrec: NumberRecord =>
        if (logger.isTraceEnabled) {
          val formatIndex = formatListener.getFormatIndex(numrec)
          val formatString = formatListener.getFormatString(numrec)
          val builtInFormat = HSSFDataFormat.getBuiltinFormat(formatIndex.toShort)
          logger.trace(s"ok numrec: $numrec and formatListener.getFormatIndex(numrec): $formatIndex, formatListener.getFormatString($formatIndex): $formatString, HSSFDataFormat.getBuiltinFormat($formatIndex): $builtInFormat, formatListener.formatNumberDateCell(numrec): ${formatListener.formatNumberDateCell(numrec)}")
        }
        Some(Column(numrec.getRow, numrec.getColumn, formatListener.formatNumberDateCell(numrec)))
      
      case rkrec: RKRecord =>
        logger.warn("Unsupported Cell Type: RKRecord")
        Some(Column(rkrec.getRow, rkrec.getColumn, ""))
      
      case mc: MissingCellDummyRecord =>
        Some(Column(mc.getRow, mc.getColumn, ""))
      
      // Ignore any other records
      case _ => None
    }

    column.foreach { (c: Column) =>
      // Handle new row
      if (c.row != lastRowNumber) lastColumnNumber = -1 
      
      // If we got a column, add it to the row
      rowBuilder += ExcelFlatFileReader.nbspTrim(c.value)

      // Update column and row count
      lastRowNumber = c.row
      lastColumnNumber = c.column
    }

    // Handle end of row
    if (record.isInstanceOf[LastCellOfRowDummyRecord]) {
      val values: Vector[String] = rowBuilder.result()
      rowBuilder = Vector.newBuilder[String] // Cleanup

      f(Success(FlatFileParsedRow(values, rawRow = "", lastRowNumber + 1)))

      lastColumnNumber = -1
    }
  }
}
