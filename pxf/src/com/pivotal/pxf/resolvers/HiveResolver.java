package com.pivotal.pxf.resolvers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.JavaUtils;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.*;
import org.apache.hadoop.hive.serde2.objectinspector.*;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.*;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;

import com.pivotal.pxf.exception.BadRecordException;
import com.pivotal.pxf.exception.FragmenterUserDataException;
import com.pivotal.pxf.format.OneField;
import com.pivotal.pxf.format.OneRow;
import com.pivotal.pxf.fragmenters.HiveDataFragmenter;
import com.pivotal.pxf.hadoop.io.GPDBWritable;
import com.pivotal.pxf.utilities.InputData;
import com.pivotal.pxf.utilities.RecordkeyAdapter;

/*
 * Class HiveResolver handles deserialization of records that were serialized 
 * using Hadoop's Hive serialization framework. HiveResolver implements
 * Resolver abstract class exposing one method: GetFields
 */
/*
TODO - remove SupressWarning once Hive resolves the problem described below
This line and the change of the deserialiazer member to Object instead of the original Deserializer...., All this changes stem from the same issue. 
In 0.11.0 The API changed and all Serde types extend a new interface - AbstractSerde. 
But this change was not adopted by the OrcSerde (which was also introduced in Hive 0.11.0). 
In order to cope with this inconsistency... this bit of juggling has been necessary.
*/
@SuppressWarnings("deprecation")
public class HiveResolver extends Resolver
{
	private RecordkeyAdapter recordkeyAdapter = new RecordkeyAdapter();
	private Object deserializer;
	private Properties serdeProperties;
	private List<OneField> partitionFields;
	private String inputFormatName;
	private String serdeName; 
	private String propsString;
	private String partitionKeys;
			
	public HiveResolver(InputData input) throws Exception
	{
		super(input);
		
		ParseUserData(input);
		InitSerde();
		InitPartitionFields();
	}

	/*
	 * parse user data string (arrived from fragmenter)
	 */
	private void ParseUserData(InputData input) throws Exception
	{
		final int EXPECTED_NUM_OF_TOKS = 4;
		
		String userData = new String(input.getFragmentUserData());
		String[] toks = userData.split(HiveDataFragmenter.HIVE_UD_DELIM);	
		
		if (toks.length != EXPECTED_NUM_OF_TOKS)
			throw new FragmenterUserDataException("HiveResolver expected " + EXPECTED_NUM_OF_TOKS + " tokens, but got " + toks.length);
		
		inputFormatName = toks[0];
		serdeName = toks[1]; 
		propsString = toks[2];
		partitionKeys = toks[3];
	}
	
	/*
	 * GetFields returns a list of the fields of one record.
	 * Each record field is represented by a OneField item.
	 * OneField item contains two fields: an integer representing the field type and a Java
	 * Object representing the field value.
	 */
	public List<OneField> GetFields(OneRow onerow) throws Exception
	{
		List<OneField> record =  new LinkedList<OneField>();
		
		Object tuple = ((SerDe)deserializer).deserialize((Writable)onerow.getData());
		ObjectInspector oi = ((SerDe)deserializer).getObjectInspector();
		
		traverseTuple(tuple, oi, record);
		/* We follow Hive convention. Partition fields are always added at the end of the record*/
		addPartitionKeyValues(record);
		
		return record;
	}
	
	/*
	 * Get and init the deserializer for the records of this Hive data fragment
	 */
	private void InitSerde() throws Exception
	{
		Class<?> c = Class.forName(serdeName, true, JavaUtils.getClassLoader());
		deserializer = (SerDe)c.newInstance();
		serdeProperties = new Properties();
		ByteArrayInputStream inStream = new ByteArrayInputStream(propsString.getBytes());
		serdeProperties.load(inStream);	
		((SerDe)deserializer).initialize(new JobConf(new Configuration(), HiveResolver.class), serdeProperties);
	}
	
	/*
	 * The partition fields are initialized  one time  base on userData provided by the fragmenter
	 */
	private void InitPartitionFields()
	{
		partitionFields	= new LinkedList<OneField>();
		if (partitionKeys.compareTo(HiveDataFragmenter.HIVE_NO_PART_TBL) == 0)
			return;
		
		String[] partitionLevels = partitionKeys.split(HiveDataFragmenter.HIVE_PARTITIONS_DELIM);
		for (String partLevel : partitionLevels)
		{
			SimpleDateFormat dateFormat = new SimpleDateFormat();
			String[] levelKey = partLevel.split(HiveDataFragmenter.HIVE_1_PART_DELIM);
			String name = levelKey[0];
			String type = levelKey[1];
			String val = levelKey[2];
			
			if (type.compareTo(serdeConstants.STRING_TYPE_NAME) == 0)
				addOneFieldToRecord(partitionFields, GPDBWritable.TEXT, val);
			else if (type.compareTo(serdeConstants.SMALLINT_TYPE_NAME) == 0)
				addOneFieldToRecord(partitionFields, GPDBWritable.SMALLINT, Short.parseShort(val));
			else if (type.compareTo(serdeConstants.INT_TYPE_NAME) == 0)
				addOneFieldToRecord(partitionFields, GPDBWritable.INTEGER, Integer.parseInt(val));
			else if (type.compareTo(serdeConstants.BIGINT_TYPE_NAME) == 0)
				addOneFieldToRecord(partitionFields, GPDBWritable.BIGINT, Long.parseLong(val));
			else if (type.compareTo(serdeConstants.FLOAT_TYPE_NAME) == 0)
				addOneFieldToRecord(partitionFields, GPDBWritable.REAL, Float.parseFloat(val));
			else if (type.compareTo(serdeConstants.DOUBLE_TYPE_NAME) == 0)
				addOneFieldToRecord(partitionFields, GPDBWritable.FLOAT8, Double.parseDouble(val));
			else if (type.compareTo(serdeConstants.TIMESTAMP_TYPE_NAME) == 0)
				addOneFieldToRecord(partitionFields, GPDBWritable.TIMESTAMP, Timestamp.valueOf(val));
			else if (type.compareTo(serdeConstants.DECIMAL_TYPE_NAME) == 0)
				addOneFieldToRecord(partitionFields, GPDBWritable.NUMERIC, new HiveDecimal(val).bigDecimalValue().toString());
			else 
				throw new RuntimeException("Unknown type: " + type);
		}		
	}
	
	private void addPartitionKeyValues(List<OneField> record)
	{
		for (OneField field : partitionFields)
			record.add(field);
	}
	
	/*
	 * If the object representing the whole record is null or if an object representing a composite sub-object (map, list,..)
	 * is null - then BadRecordException will be thrown. If a primitive field value is null, then a null will appear for 
	 * the field in the record in the query result.
	 */
	public void traverseTuple(Object obj, ObjectInspector objInspector, List<OneField> record) throws IOException, BadRecordException
	{
		
		ObjectInspector.Category category = objInspector.getCategory();
		
		if ((obj == null) && (category != ObjectInspector.Category.PRIMITIVE)) 
			throw new BadRecordException("NULL Hive composite object");
		
		List<?> list;
		switch (category) 
		{
			case PRIMITIVE:
				resolvePrimitive(obj, (PrimitiveObjectInspector) objInspector, record);
				return;
			case LIST:
				ListObjectInspector loi = (ListObjectInspector) objInspector;
				list = loi.getList(obj);
				ObjectInspector eoi = loi.getListElementObjectInspector();
				if (list == null)
					throw new BadRecordException("Illegal value NULL for Hive data type List");
					
				for (int i = 0; i < list.size(); i++) 
					traverseTuple(list.get(i), eoi, record);
				return;
			case MAP:
				MapObjectInspector moi = (MapObjectInspector) objInspector;
				ObjectInspector koi = moi.getMapKeyObjectInspector();
				ObjectInspector voi = moi.getMapValueObjectInspector();
				Map<?, ?> map = moi.getMap(obj);
				if (map == null)
					throw new BadRecordException("Illegal value NULL for Hive data type Map");
				
				for (Map.Entry<?, ?> entry : map.entrySet()) 
				{
					traverseTuple(entry.getKey(), koi, record);
					traverseTuple(entry.getValue(), voi, record);
				}
				return;
			case STRUCT:
				StructObjectInspector soi = (StructObjectInspector) objInspector;
				List<? extends StructField> fields = soi.getAllStructFieldRefs();
				list = soi.getStructFieldsDataAsList(obj);
				if (list == null)
					throw new BadRecordException("Illegal value NULL for Hive data type Struct");
		
				for (int i = 0; i < list.size(); i++) 
					traverseTuple(list.get(i), fields.get(i).getFieldObjectInspector(), record);
				return;
			case UNION:
				UnionObjectInspector uoi = (UnionObjectInspector) objInspector;
				List<? extends ObjectInspector> ois = uoi.getObjectInspectors();
				if (ois == null)
					throw new BadRecordException("Illegal value NULL for Hive data type Union");

				traverseTuple(uoi.getField(obj), ois.get(uoi.getTag(obj)), record);
				return;
			default:
				break;
		}
		
		throw new RuntimeException("Unknown category type: "
								   + objInspector.getCategory());
	}
	
	public void resolvePrimitive(Object o, PrimitiveObjectInspector oi, List<OneField> record) throws IOException
	{
		Object val;
		switch (oi.getPrimitiveCategory()) 
		{
			case BOOLEAN: 
			{
				val = (o != null) ? ((BooleanObjectInspector) oi).get(o) : o;
				addOneFieldToRecord(record, GPDBWritable.BOOLEAN, val);
				break;
			}
			case SHORT: {
				val = (o != null) ? ((ShortObjectInspector) oi).get(o) : o;
				addOneFieldToRecord(record, GPDBWritable.SMALLINT, val);
				break;
			}
			case INT: {
				val = (o != null) ? ((IntObjectInspector) oi).get(o) : o;
				addOneFieldToRecord(record, GPDBWritable.INTEGER, val);
				break;
			}
			case LONG: {
				val = (o != null) ? ((LongObjectInspector) oi).get(o) : o;
				addOneFieldToRecord(record, GPDBWritable.BIGINT, val);
				break;
			}
			case FLOAT: {
				val = (o != null) ? ((FloatObjectInspector) oi).get(o) : o;
				addOneFieldToRecord(record, GPDBWritable.REAL, val);
				break;
			}
			case DOUBLE: {
				val = (o != null) ? ((DoubleObjectInspector) oi).get(o) : o;
				addOneFieldToRecord(record, GPDBWritable.FLOAT8, val);
				break;
			}
			case DECIMAL: {
				String sVal = null;
				if (o != null)
				{
					BigDecimal bd = ((HiveDecimalObjectInspector) oi).getPrimitiveJavaObject(o).bigDecimalValue();
					sVal = bd.toString();
				}
				addOneFieldToRecord(record, GPDBWritable.NUMERIC, sVal);
				break;
			}
			case STRING: {
				val = (o != null) ? ((StringObjectInspector) oi).getPrimitiveJavaObject(o) : o;
				addOneFieldToRecord(record, GPDBWritable.TEXT, val);
				break;
			}
			case BINARY: {
				byte[] toEncode = null;
				if (o != null)
				{
					BytesWritable bw = ((BinaryObjectInspector) oi).getPrimitiveWritableObject(o);
					toEncode = new byte[bw.getLength()];
					System.arraycopy(bw.getBytes(), 0,toEncode, 0, bw.getLength());
				}
				addOneFieldToRecord(record, GPDBWritable.BYTEA, toEncode);
				break;
			}
			case TIMESTAMP: {
				val = (o != null) ? ((TimestampObjectInspector) oi).getPrimitiveJavaObject(o) : o;
				addOneFieldToRecord(record, GPDBWritable.TIMESTAMP, val);
				break;
			}
			default: {
				throw new RuntimeException("Unknown type: " + oi.getPrimitiveCategory().toString());
			}
		}		
	}

	void addOneFieldToRecord(List<OneField> record, int gpdbWritableType, Object val)
	{
		OneField oneField = new OneField();

		oneField.type = gpdbWritableType;
		oneField.val = val;
		record.add(oneField);
	}
}
