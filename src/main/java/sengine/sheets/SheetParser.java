package sengine.sheets;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BooleanArray;
import com.badlogic.gdx.utils.IntArray;
import com.opencsv.CSVReader;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

import static sengine.sheets.SheetParser.FieldParseResult.*;

/**
 * Created by Azmi on 3/13/2017.
 */

public class SheetParser {

    private static final String ESCAPE = ">";
    private static final String RETURN = "<";

    private static final Pattern matcher = Pattern.compile("\\s*,\\s*");

    // Helpers
    public static String[] splitStringCSV(String csv) {
        String[] values = matcher.split(csv.trim());
        if(values.length == 1 && values[0].isEmpty())
            return new String[0];
        return values;
    }

    public static boolean[] splitBooleanCSV(String csv) {
        String[] values = splitStringCSV(csv);
        boolean[] bools = new boolean[values.length];
        for(int c = 0; c < values.length; c++) {
            bools[c] = values[c].equalsIgnoreCase("true") || values[c].equalsIgnoreCase("yes");
        }
        return bools;
    }

    public static int[] splitIntegerCSV(String csv) {
        String[] values = splitStringCSV(csv);
        int[] ints = new int[values.length];
        for(int c = 0; c < values.length; c++) {
            ints[c] = Integer.parseInt(values[c]);
        }
        return ints;
    }

    public static float[] splitFloatCSV(String csv) {
        String[] values = splitStringCSV(csv);
        float[] floats = new float[values.length];
        for(int c = 0; c < values.length; c++) {
            floats[c] = Float.parseFloat(values[c]);
        }
        return floats;
    }

    public static long[] splitLongCSV(String csv) {
        String[] values = splitStringCSV(csv);
        long[] longs = new long[values.length];
        for(int c = 0; c < values.length; c++) {
            longs[c] = Long.parseLong(values[c]);
        }
        return longs;
    }

    public static double[] splitDoubleCSV(String csv) {
        String[] values = splitStringCSV(csv);
        double[] doubles = new double[values.length];
        for(int c = 0; c < values.length; c++) {
            doubles[c] = Double.parseDouble(values[c]);
        }
        return doubles;
    }






    static boolean softStringEquals(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        int c1 = 0;
        int c2 = 0;
        char ch1 = 0;
        char ch2 = 0;

        while(true) {
            // Get a usable characters
            while(c1 < len1 && (Character.isWhitespace(ch1 = s1.charAt(c1)) || ch1 == '_'))
                c1++;
            while(c2 < len2 && (Character.isWhitespace(ch2 = s2.charAt(c2)) || ch2 == '_'))
                c2++;

            if(c1 == len1 || c2 == len2) {
                // Finishing
                if(c1 == len1 && c2 == len2)
                    return true;     // didnt return till the end of both strings, so must be equal
                // Else one ended before the other could, must not be equal
                return false;
            }

            // Else compare both characters
            if(Character.toLowerCase(ch1) != Character.toLowerCase(ch2))
                return false;

            // Compare next
            c1++;
            c2++;
        }
    }

    private static String positionToString(long position) {
        int row = (int) (position >>> 32);
        int column = (int) position;

        String s = "";

        do {
            int n = column % 26;
            column /= 26;

            if(column > 0 || s.isEmpty())
                s = (char)('A' + n) + s;
            else // if(column == 0)
                s = (char)('A' + n - 1) + s;
        } while(column > 0);

        s = s + (row + 1);

        return s;
    }



    private final Array<String[]> lines = new Array<>(String[].class);
    private final IntArray lineShifts = new IntArray();
    private final Array<long[]> linePositions = new Array<>(long[].class);
    private final BooleanArray objectFilledStack = new BooleanArray();
    private int currentLine = 0;

    public String[][] parseSheetArray(int baseShift) {
        Array<String[]> sheet = new Array<>(String[].class);
        boolean isNextLine = false;
        for(; currentLine < lines.size; currentLine++) {
            // Check shift
            int shift = isNextLine ? lineShifts.items[currentLine] : baseShift;

            if(shift < baseShift)
                break;              // shifting up
            String[] values = lines.items[currentLine];
            if(!isNextLine) {
                // For first line, remove any values before baseshift
                values = Arrays.copyOf(values, values.length);
                for(int c = 0; c < baseShift; c++)
                    values[c] = null;
            }

            sheet.add(values);

            // Try next line now
            isNextLine = true;
        }

        return sheet.toArray();
    }

    private Object parsePrimitiveArray(Class<?> type, Primitive primitive, int baseShift) {
        Class<?> wrapperArrayType = primitive.wrapperType != null ? primitive.wrapperType : type;
        Array<Object> array = new Array<>(wrapperArrayType);

        boolean isNextLine = false;
        for(; currentLine < lines.size; currentLine++) {
            // Check shift
            int shift = isNextLine ? lineShifts.items[currentLine] : baseShift;
            if(shift < baseShift)
                break;              // shifting up
            String[] values = lines.items[currentLine];

            for(; shift < values.length; shift++) {
                String value = values[shift];
                if(value == null)
                    break;      // end of line
                try {
                    array.add(primitive.parse(type, value));
                } catch (Throwable e) {
                    String error = String.format(Locale.US, "%s: error in array %s[%d]",
                            positionToString(linePositions.items[currentLine][shift]),
                            type.getSimpleName(),
                            array.size
                    );
                    throw new ParseException(error, e);
                }
            }
            // Try next line now
            isNextLine = true;
        }

        if(wrapperArrayType == type)
            return array.toArray();

        // Else need to convert to primitive type
        if(wrapperArrayType == Byte.class)
            return ArrayUtils.toPrimitive((Byte[]) array.toArray());
        else if(wrapperArrayType == Character.class)
            return ArrayUtils.toPrimitive((Character[]) array.toArray());
        else if(wrapperArrayType == Short.class)
            return ArrayUtils.toPrimitive((Short[]) array.toArray());
        else if(wrapperArrayType == Integer.class)
            return ArrayUtils.toPrimitive((Integer[]) array.toArray());
        else if(wrapperArrayType == Long.class)
            return ArrayUtils.toPrimitive((Long[]) array.toArray());
        else if(wrapperArrayType == Float.class)
            return ArrayUtils.toPrimitive((Float[]) array.toArray());
        else if(wrapperArrayType == Double.class)
            return ArrayUtils.toPrimitive((Double[]) array.toArray());
        else // if(wrapperArrayType == Boolean.class)
            return ArrayUtils.toPrimitive((Boolean[]) array.toArray());
    }

    private Object parseObjectArray(Class<?> componentType, int baseShift) {
        // Its an array of objects
        Array<Object> array = new Array<>(componentType);
        boolean isNextLine = false;
        while(currentLine < lines.size) {
            // Check shift
            int shift = isNextLine ? lineShifts.items[currentLine] : baseShift;
            if(shift < baseShift)
                break;              // shifting up
            int line = currentLine;
            Object component;
            try {
                component = parse(componentType, null, baseShift);
            } catch (Throwable e) {
                String error = String.format(Locale.US, "%s-??: error in array %s[%d]",
                        positionToString(linePositions.items[line][baseShift]),
                        componentType.getSimpleName(),
                        array.size
                );
                throw new ParseException(error, e);
            }
            if(component == null)
                break;          //  Cannot parse, either shift change or end of sheet
            // Else parsed, add to array
            array.add(component);
            // Increase line if not yet
            if(currentLine == line)
                currentLine++;
            isNextLine = true;
        }
        // Done return array
        return array.toArray();
    }

    private int adjustBaseShift(int baseShift) {
        if(currentLine >= lines.size)
            return -1;      // sheet ended
        String[] values = lines.items[currentLine];
        for(int c = baseShift; c < values.length; c++) {
            if(values[c] != null)
                return c;           // baseShift is valid at the current line
        }
        // Else need find on the next line
        currentLine++;
        if(currentLine >= lines.size)
            return -1;      // sheet ended
        int shift = lineShifts.items[currentLine];
        if(shift < baseShift)
            return -1;              // shifting up
        return shift;               // Found a valid baseShift on the next line
    }

    private long findLastPosition(int line) {
        String[] values = lines.items[line];
        long position = -1;
        for (int c = 0; c < values.length; c++) {
            if (values[c] != null) {
                position = linePositions.items[line][c];
            }
        }
        return position;
    }

    enum FieldParseResult {
        END_OF_LINE,
        SKIPPED,
        PARSED_PRIMITIVE,
        PARSED_OBJECT
    }

    private FieldParseResult parseField(Serializer<?> serializer, Object object, int fieldIndex, int shift) {
        if(shift >= lines.items[currentLine].length)
            return END_OF_LINE;
        String value = lines.items[currentLine][shift];
        if (value == null)
            return END_OF_LINE;          // end of lne
        if (value.equals(ESCAPE))
            return SKIPPED;           // skipped
        int line = currentLine;
        Field field = serializer.fields[fieldIndex];
        Primitive fieldPrimitive = serializer.fieldPrimitives[fieldIndex];
        Class<Object> fieldType = (Class<Object>) serializer.fieldTypes[fieldIndex];
        try {
            if(field == null) {
                // It's a field method
                Method fieldMethod = serializer.fieldMethods[fieldIndex];
                Object parameter;
                if(fieldPrimitive != null) {
                    // Else its a primitive, parse and save
                    if (value.startsWith(ESCAPE))            // Escape
                        value = value.substring(ESCAPE.length());
                    parameter = fieldPrimitive.parse(fieldType, value);
                    fieldMethod.invoke(object, parameter);      // Let field method to handle this field
                    return PARSED_PRIMITIVE;
                }
                else {
                    // Else its an object
                    parameter = parse(fieldType, null, shift);       // will always return something
                    fieldMethod.invoke(object, parameter);
                    return PARSED_OBJECT;
                }
            }
            else if (fieldPrimitive != null) {
                // Else primitive field
                if (value.startsWith(ESCAPE))            // Escape
                    value = value.substring(ESCAPE.length());
                fieldPrimitive.read(field, object, value);
                return PARSED_PRIMITIVE;
            }
            else {
                // It's field but its an object
                Object existing = field.get(object);            // use existing if possible
                Object parsed = parse(fieldType, existing, shift);       // will always return something
                field.set(object, parsed);
                return PARSED_OBJECT;

            }
        }
        catch (Throwable e) {
            // Build error string
            String message = fieldPrimitive != null ? "%s: error in field %s.%s (%s)" : "%s-??: error in object %s.%s (%s)";
            String error = String.format(Locale.US, message,
                    positionToString(linePositions.items[line][shift]),
                    serializer.type.getSimpleName(),
                    serializer.fieldNames[fieldIndex],
                    fieldType.getSimpleName()
            );
            throw new ParseException(error, e);
        }
    }

    private void enforceLineEnd(int shift) {
        String[] values = lines.items[currentLine];
        for (int c = shift; c < values.length; c++) {
            if (values[c] != null)
                throw new ParseException(positionToString(linePositions.items[currentLine][c]) + ": no values expected here");
        }
    }

    /**
     * primitive array types not supported, multidimensional arrays not supported
     * @param type
     * @param baseShift
     * @param <T>
     * @return
     */
    private <T> T parse(Class<T> type, T existingObject, int baseShift) {
        // Adjust base shift
        baseShift = adjustBaseShift(baseShift);
        if(baseShift == -1)
            return null;            // Either sheet ended, or shifting up

        int lineStarted = currentLine;      // Used for error logging

        // Check if primitive
        Primitive primitive = Primitive.get(type);
        if(primitive != null) {
            String value = lines.items[currentLine][baseShift];
            // Check for unexpected values after
            enforceLineEnd(baseShift + 1);
            return (T) primitive.parse(type, value);
        }

        // Check if array
        if(type.isArray()) {
            Class<?> componentType = type.getComponentType();
            primitive = Primitive.get(componentType);
            Object array;
            if(primitive != null)
                array = parsePrimitiveArray(componentType, primitive, baseShift);
            else if(componentType == String[].class)
                array = parseSheetArray(baseShift);
            else
                array = parseObjectArray(componentType, baseShift);

            if(existingObject != null) {
                int length = java.lang.reflect.Array.getLength(array);
                System.arraycopy(array, 0, existingObject, 0, length);
                return existingObject;
            }

            return (T) array;
        }

        // Else is an object

        // Get serializer
        Serializer<T> serializer = Serializer.get(type);

        // Instantiate if needed
        T object = existingObject;
        if(object == null)
            object = serializer.newInstance();

        // Start
        try {
            // Add to stack
            SheetStack.add(object);
            // Add filled object stack
            int objectFilledOffset = objectFilledStack.size;
            for(int c = 0; c < serializer.fields.length; c++)
                objectFilledStack.add(false);

            // Callback
            if(object instanceof OnSheetStarted) {
                try {
                    ((OnSheetStarted) object).onSheetStarted();
                }
                catch (Throwable e) {
                    throw new ParseException(positionToString(linePositions.items[currentLine][baseShift]) + ": error starting object " + type.getSimpleName(), e);
                }
            }

            boolean isNextLine = false;
            while (currentLine < lines.size) {
                // Remember line
                int line = currentLine;

                // Check shift
                int shift = isNextLine ? lineShifts.items[currentLine] : baseShift;
                if (shift < baseShift)
                    break;      // shifting up
                baseShift = shift;              // accept forward shifts, but not back

                String[] values = lines.items[currentLine];
                long[] positions = linePositions.items[currentLine];

                // First line, possibility of horizontal unpacking, first value must not match any known field names
                String value = values[shift];
                int index = -1;
                boolean isMethod = false;
                if (value.startsWith(RETURN)) {
                    if (isNextLine)
                        break;          // Return indicator is used to start a new object
                    value = value.substring(RETURN.length());
                    // field or method names cannot be escaped
                    index = serializer.findMethod(value);
                    if (index != -1)
                        isMethod = true;
                    else {
                        index = serializer.findField(value);
                        if (index == -1) {
                            throw new ParseException(String.format(Locale.US, "%s: explicit selection of unknown method or field %s.%s",
                                    positionToString(positions[shift]),
                                    type.getSimpleName(),
                                    value
                            ));
                        }
                    }
                }
                if (!value.startsWith(ESCAPE)) {
                    // field or method names cannot be escaped
                    index = serializer.findMethod(value);
                    if (index != -1)
                        isMethod = true;
                    else
                        index = serializer.findField(value);
                }

                // One of 3 possibilities, horizontal parsing, field, or method
                if (index == -1) {
                    // First value is neither field nor method name, start horizontal parsing
                    if (isNextLine)
                        break;          // cannot use multiple horizontal unpacks, must be another object in an array

                    boolean endOfLine = false;
                    boolean endsWithObject = false;

                    // Start parsing fields in order
                    for (int c = 0; c < serializer.fields.length; c++) {
                        FieldParseResult result = parseField(serializer, object, c, shift + c);

                        // Check result
                        if(result == END_OF_LINE) {
                            endOfLine = true;
                            break;
                        }
                        else if(result == PARSED_OBJECT) {
                            objectFilledStack.items[objectFilledOffset + c] = true;
                            endsWithObject = true;
                            break;
                        }
                        else if(result == PARSED_PRIMITIVE)
                            objectFilledStack.items[objectFilledOffset + c] = true;
                    }

                    // Make sure no unexpected values
                    if(!endsWithObject && !endOfLine)
                        enforceLineEnd(shift + serializer.fields.length);
                }
                else if (!isMethod) {
                    // First value is a field name
                    FieldParseResult result = parseField(serializer, object, index, shift + 1);

                    // Check result;
                    if (result == END_OF_LINE || result == SKIPPED) {
                        throw new ParseException(String.format(Locale.US, "%s: missing field value %s.%s (%s)",
                                positionToString(positions[shift]),
                                type.getSimpleName(),
                                serializer.fieldNames[index],
                                serializer.fieldTypes[index].getSimpleName()
                        ));
                    }
                    else if (result == PARSED_PRIMITIVE) {
                        objectFilledStack.items[objectFilledOffset + index] = true;
                        enforceLineEnd(shift + 2);      // If not an object, make sure the are no unwanted values at the end
                    }
                    else // if (result == PARSED_OBJECT)
                        objectFilledStack.items[objectFilledOffset + index] = true;
                }
                else {
                    // Else first value is a method name
                    Method method = serializer.methods[index];
                    Class<?>[] parameterTypes = serializer.methodParameterTypes[index];

                    Object[] parameters = new Object[parameterTypes.length];
                    boolean endsWithObject = false;

                    for (int c = 0; c < parameterTypes.length && (shift + c + 1) < values.length; c++) {
                        value = values[shift + c + 1];
                        Class<?> parameterType = parameterTypes[c];
                        primitive = Primitive.get(parameterType);
                        if(value == null) {
                            // Set default primitive values for the rest of the parameters
                            for (int i = c; i < parameterTypes.length; i++) {
                                parameterType = parameterTypes[i];
                                primitive = Primitive.get(parameterType);
                                if(primitive != null)
                                    parameters[i] = primitive.defaultValue();
                            }
                            break;
                        }
                        if (value.equals(ESCAPE)) {
                            // Set default primitive value for this parameter
                            if(primitive != null)
                                parameters[c] = primitive.defaultValue();
                            continue;
                        }
                        try {
                            if(primitive != null) {
                                if (value.startsWith(ESCAPE))
                                    value = value.substring(ESCAPE.length());       // Escape
                                parameters[c] = primitive.parse(parameterType, value);
                            }
                            else {
                                endsWithObject = true;
                                // Else its an object, recurse here and stop horizontal unpacking
                                parameters[c] = parse(parameterType, null, shift + c + 1);       // will always return something
                                // Set default primitive values for the rest of the parameters
                                for (int i = c + 1; i < parameterTypes.length; i++) {
                                    parameterType = parameterTypes[i];
                                    primitive = Primitive.get(parameterType);
                                    if(primitive != null)
                                        parameters[i] = primitive.defaultValue();
                                }
                                break;
                            }
                        } catch (Throwable e) {
                            // Build error string
                            String parameterList = "";
                            for (int i = 0; i < parameterTypes.length; i++) {
                                if (i > 0)
                                    parameterList += ", ";
                                parameterList += parameterTypes[i].getSimpleName();
                            }
                            String message = endsWithObject ?
                                    "%s-??: error in object-%d for method %s.%s (%s)" :
                                    "%s: error in parameter-%d for method %s.%s (%s)";
                            String error = String.format(Locale.US, message,
                                    positionToString(positions[shift + c + 1]),
                                    c + 1,
                                    type.getSimpleName(),
                                    method.getName(),
                                    parameterList
                            );

                            throw new ParseException(error, e);
                        }
                    }

                    // Check for unexpected values
                    if(!endsWithObject)
                        enforceLineEnd(shift + parameterTypes.length + 1);

                    // Evaluate null parameters
                    for(int c = 0; c < parameters.length; c++) {
                        if(parameters[c] != null)
                            continue;
                        Class<?> parameterType = parameterTypes[c];
                        if(parameterType == int.class)
                            parameters[c] = 0;
                        if(parameterType == long.class)
                            parameters[c] = 0L;
                        if(parameterType == float.class)
                            parameters[c] = 0f;
                        if(parameterType == double.class)
                            parameters[c] = 0.0;
                    }

                    // Call this method
                    try {
                        method.invoke(object, parameters);
                    } catch (Throwable e) {
                        // Build error string
                        String parameterList = "";
                        for (int i = 0; i < parameterTypes.length; i++) {
                            if (i > 0)
                                parameterList += ", ";
                            parameterList += parameterTypes[i].getSimpleName();
                        }
                        String error = String.format(Locale.US, "%s: error in method %s.%s (%s)",
                                positionToString(positions[shift]),
                                type.getSimpleName(),
                                method.getName(),
                                parameterList
                        );

                        throw new ParseException(error, e);
                    }

                    // If is field method, honor required fields
                    for(int c = 0; c < serializer.fieldMethods.length; c++) {
                        if(serializer.fieldMethods[c] == method) {
                            objectFilledStack.items[objectFilledOffset + c] = true;
                            break;
                        }
                    }
                }

                // Done, if still the same line, increment
                if (line == currentLine)
                    currentLine++;

                // Else must have moved down from parsing an object
                isNextLine = true;
            }

            // Check required fields
            for(int c = 0; c < serializer.fieldRequired.length; c++) {
                if(serializer.fieldRequired[c] && !objectFilledStack.items[objectFilledOffset + c])
                    throw new ParseException("Missing required field " + type.getSimpleName() + "." + serializer.fieldNames[c]);

            }

            // Callback
            if(object instanceof OnSheetEnded) {
                try {
                    ((OnSheetEnded) object).onSheetEnded();
                }
                catch (Throwable e) {
                    throw new ParseException(positionToString(findLastPosition(currentLine == lineStarted ? currentLine : currentLine - 1)) + ": error ending object " + type.getSimpleName(), e);
                }
            }

        } finally {
            // Add filled object stack
            for(int c = 0; c < serializer.fields.length; c++)
                objectFilledStack.pop();
            // Remove from stack
            SheetStack.pop();
        }

        // Parsed till end of sheet
        return object;
    }

    public <T> T parseXLS(InputStream s, String sheetName, Class<T> type, T existingObject) {
        try {
            // Reset
            clear();

            // Open workbook
            Workbook wb = new XSSFWorkbook(s);
            Sheet sheet = wb.getSheet(sheetName);
            if (sheet == null)
                throw new ParseException("Sheet not found: " + sheetName);

            // Evaluate all cells
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();

            int lastRow = sheet.getLastRowNum();

            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null)
                    continue;       // UB or empty row

                int lastCell = row.getLastCellNum();
                if(lastCell == -1)
                    continue;
                String[] values = new String[lastCell];

                for (int c = 0; c < lastCell; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null) {
                        // Evaluate and format cell
                        evaluator.evaluateInCell(cell);
                        values[c] = formatter.formatCellValue(cell);
                    }
                }

                // Add row
                addRow(r, values);
            }

        } catch (Throwable e) {
            clear();
            throw new ParseException("Error reading xls sheet: " + sheetName, e);
        }

        // Done reading all rows, parse
        return parse(type, existingObject);
    }

    public <T> T parseXLS(InputStream s, Class<T> type, T existingObject) {
        try {
            // Open workbook
            Workbook wb = new XSSFWorkbook(s);

            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();

            // Parse all sheets
            int count = wb.getNumberOfSheets();
            for(int index = 0; index < count; index++) {
                Sheet sheet = wb.getSheetAt(index);

                try {
                    // Reset
                    clear();

                    // Evaluate all cells
                    int lastRow = sheet.getLastRowNum();

                    for (int r = 0; r <= lastRow; r++) {
                        Row row = sheet.getRow(r);
                        if (row == null)
                            continue;       // UB or empty row

                        int lastCell = row.getLastCellNum();
                        if (lastCell == -1)
                            continue;
                        String[] values = new String[lastCell];

                        for (int c = 0; c < lastCell; c++) {
                            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                            if (cell != null) {
                                // Evaluate and format cell
                                evaluator.evaluateInCell(cell);
                                values[c] = formatter.formatCellValue(cell);
                            }
                        }

                        // Add row
                        addRow(r, values);
                    }

                    // Parse
                    existingObject = parse(type, existingObject);

                } catch (Throwable e) {
                    throw new ParseException("Error in sheet: " + sheet.getSheetName(), e);
                }
            }
        } catch (IOException e) {
            throw new ParseException("Error reading xls", e);
        }


        return existingObject;
    }

    public <T> T parseCSV(String csv, Class<T> type) {
        return parseCSV(csv, type, null);
    }

    public <T> T parseCSV(String csv, Class<T> type, T existingObject) {
        return parseCSV(csv, type, existingObject, ',');
    }

    public <T> T parseCSV(String csv, Class<T> type, T existingObject, char separator) {
        try {
            // Reset
            clear();

            // Read entire csv
            CSVReader reader = new CSVReader(new StringReader(csv), separator);
            String[] values;

            int row = 0;
            while ((values = reader.readNext()) != null) {
                addRow(row, values);
                row++;
            }

        } catch (Throwable e) {
            clear();
            throw new ParseException("Error reading csv sheet", e);
        }

        // Done reading all rows, parse
        return parse(type, existingObject);
    }

    public void addRow(int row, String[] values) {
        // Cleanup values
        // First remove all comments or empty strings and identify shift
        int shift = -1;
        boolean hasCommented = false;
        for (int c = 0; c < values.length; c++) {
            if(hasCommented)
                values[c] = null;     // once commented, remove all subsequent
            else {
                String value = values[c];
                if(value == null)
                    continue;
                if(!value.startsWith(ESCAPE))
                    values[c] = value = value.trim();
                if (value.isEmpty())
                    values[c] = null;     // remove empty
                else if (value.startsWith("//")) {
                    values[c] = null;     // remove comment, and remember to remove all subsequent
                    hasCommented = true;
                }
                else if (shift == -1)
                    shift = c;      // recognize first column
            }
        }

        // Skip empty lines
        if(shift == -1)
            return;

        // Remember positions
        long[] positions = new long[values.length];
        for (int c = 0; c < values.length; c++) {
            positions[c] = ((long)row << 32) | (c);
        }

        // Now compact columns
        int offset = 0;
        for (int c = shift; c < values.length; c++) {
            String value = values[c];
            long position = positions[c];
            if(value == null)
                offset++;                  // empty spaces in between first column, indicate shift
            else {
                // Another value, compact them
                values[c] = null;
                values[c - offset] = value;
                positions[c - offset] = position;
            }
        }

        // Keep
        lines.add(values);
        lineShifts.add(shift);
        linePositions.add(positions);
    }

    public <T> T parse(Class<T> type, T existingObject) {
        // Parse to structure
        try {
            T result = parse(type, existingObject, 0);

            // Check unexpected values
            if (currentLine < lines.size)
                throw new ParseException(positionToString(findLastPosition(currentLine)) + ": no values expected here");

            // Done
            return result;
        }
        finally {
            // Clear
            clear();
        }
    }

    public void clear() {
        lines.clear();
        lineShifts.clear();
        linePositions.clear();
        currentLine = 0;
    }
}
