package http;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.json.JSONException;
import org.json.JSONObject;

import knn.KNN;

import util.DatabaseOperator;
import util.Logger4Finger;

public class FingerprintingAPI {
	private String mapId = "";
	private List<Map<String, Integer>> wifiList; //MAC&RSSI��Ϣ���
	private List<HashSet<Integer>> heatmapSetList; //�洢ÿ������MAC��Ӧpos_id�Ľ���
	private Map<Integer, Integer> positionRangeMap;//ÿ�齻���Ĳ���
	private List<Map<String, Integer>> calcWifiList; //�����ݿ��ȡ����MAC&RSSI,ָ��
	private Map<Integer, Integer> positionReuslt; //������
	
	public Logger4Finger logger;
	
	public FingerprintingAPI() {
		wifiList = new ArrayList<Map<String, Integer>>();
		heatmapSetList = new ArrayList<HashSet<Integer>>();
		positionRangeMap = new HashMap<Integer, Integer>();
		calcWifiList = new ArrayList<Map<String, Integer>>();
		positionReuslt = new TreeMap<Integer, Integer>();
		
		logger = new Logger4Finger(Long.toString(new Date().getTime()));
	}
	
	//Ѱ�Ұ�������AP������λ�õ�
	public int calcHeatmap() {
		DatabaseOperator databaseOp = new DatabaseOperator();
		Connection conn = databaseOp.getConnect();
		Statement stam = null;
		ResultSet res = null;
		try {
			stam = conn.createStatement();
		} catch (SQLException e) {
			e.printStackTrace();
			return -1; //���ݿ�����ʧ��
		}
		
		for(int i = 0; i < wifiList.size(); i++) {
			Map<String, Integer> wifiInfo = wifiList.get(i);
			HashSet<Integer> hashset = new HashSet<Integer>();
			int count = 0;
			String macStr = "";
			for(Map.Entry<String, Integer> entry : wifiInfo.entrySet()) {				
				macStr += "'" + entry.getKey() + "',";
				count++;
			}
			macStr = macStr.substring(0, macStr.length() - 1);
			
			do {
				//��ȡÿ�����ݵ��ȵ�ͼ, ����countΪ��������MAC��������
				String sql = "select  t.pos_id from (select pos_id,mac from finger group by pos_id,mac) as t " +
							"where t.mac in (" + macStr + ") group by t.pos_id having count(0)=" + count;
				res = databaseOp.getResultSet(stam, sql); //ִ�����ݿ��ѯ����
				try {
					if (res.next()) { //����ѯ�����Ϊ�գ�˵���������ݹ�������count��MAC
						do {
							int pos_id = res.getInt(1); //��ȡλ�ñ�ʶ
							//��¼����
							hashset.add(pos_id);
						} while(res.next()); //������ȡ��һ����¼
						break;
					}
					count--; //����ѯ���Ϊ����count��һ��������
				} catch (SQLException e) {
					e.printStackTrace();
					return -2; //��ȡ���ݿ����
				}
			} while (count > 0); //��count>0ʱ���������Ұ�������MAC��ָ��
			if (count <= 0) {
				continue; //�޽���
			}
			logger.info(macStr + "\tcount:" + count);
			this.heatmapSetList.add(hashset);
		}
		
		try {
			if (res != null) {
				res.close();
			}
			
			stam.close();
			conn.close();
		} catch (SQLException e) {
			e.printStackTrace();
			return -3; //���ݿ�رղ���ʧ��
		}
		return 0;
	}
	
	public int getPositionRange() {
		for (int i = 0; i < this.heatmapSetList.size(); i++) {
			HashSet<Integer> hashset = this.heatmapSetList.get(i);
			Iterator<Integer> it = hashset.iterator();
			while (it.hasNext()) {
				int pos = (Integer) it.next();
				if (positionRangeMap.containsKey(pos)) {
					positionRangeMap.put(pos, positionRangeMap.get(pos)+1);
				} else {
					positionRangeMap.put(pos, 1);
				}
			}
		}
		if (positionRangeMap.isEmpty()) {
			return -1;
		}
		return 0;
	}
	
	public int calcKNN() {
		List<Map.Entry<Integer, Integer>> tmap = new ArrayList<Map.Entry<Integer, Integer>>(positionRangeMap.entrySet());
		Collections.sort(tmap, new Comparator<Map.Entry<Integer, Integer>>() {
			public int compare(Entry<Integer, Integer> o1,
					Entry<Integer, Integer> o2) {
				return o2.getValue() - o1.getValue();
			}});
		if (tmap.isEmpty()) {
			return -1;
		}
		logger.info("Range Map--" + tmap.toString());
		int count = tmap.get(0).getValue(); //��ȡ�ȵ�ͼ�������
		List<Integer> posIds = new ArrayList<Integer>();
		for (int i = 0; i < tmap.size(); i++) {
			if (tmap.get(i).getValue() == count) {
				posIds.add(tmap.get(i).getKey());
			}
		}
		logger.info("Max Stronger Heatmap->" + posIds.toString());
		List<Map<String, Integer>> dataList = this.calcWifiList;
		dataList.addAll(getWifiListFromDatabase(posIds)); //�����ݿ��ȡѡ���pos_id��Ӧ��mac,rssi��Ϣ
		List<Map<String, Integer>> testDataList = this.wifiList;
		KNN knn = new KNN();
		for (int j = 0; j < testDataList.size(); j++) {
			Map<String, Integer> testData = testDataList.get(j);
			String str = "";
			for (Map.Entry<String, Integer> entry : testData.entrySet()) {
				str += entry.getValue() + " ";
			}
			int res = Integer.parseInt(knn.knn(dataList, testData, 4));
			logger.info(str + "\t>>\t" + res);
			if (positionReuslt.containsKey(res)) {
				positionReuslt.put(res, positionReuslt.get(res) + 1);
			} else {
				positionReuslt.put(res, 1);
			}
		}
		return 0;
	}
	
	public String getPositionResult() {
		//�Խ������������
		List<Map.Entry<Integer, Integer>> tList = new ArrayList<Map.Entry<Integer, Integer>>(positionReuslt.entrySet());
		Collections.sort(tList, new Comparator<Map.Entry<Integer, Integer>>() {
			public int compare(Entry<Integer, Integer> o1,
					Entry<Integer, Integer> o2) {
				return o2.getValue() - o1.getValue();
			}});
		if (tList.isEmpty()) {
			return "";
		}
		logger.info(tList.toString());
		int maxCountResult = tList.get(0).getKey();
		JSONObject position = getPositionFromDatabase(maxCountResult);
		if (position == null) {
			logger.info("select position return null");
			return "";
		}
		JSONObject result = new JSONObject();
		try {
			result.put("code", 0);
			result.put("detail", position);
		} catch (JSONException e) {
			e.printStackTrace();
		}
		logger.info(result.toString());
		return result.toString();
	}
	
	public JSONObject getPositionFromDatabase(int posId) {
		JSONObject obj = new JSONObject();
		DatabaseOperator databaseOp = new DatabaseOperator();
		Connection conn = databaseOp.getConnect();
		Statement stam = null;
		try {
			stam = conn.createStatement();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		
		//String sql = "select x_pos,y_pos from indoor where id=" + posId + " and map_id='" + this.mapId + "';";
		String sql = "select x_pos,y_pos from indoor where id=" + posId + ";";
		logger.info("Select Result Sql:" + sql);
		ResultSet res = databaseOp.getResultSet(stam, sql);
		try {
			if(res.next()){
				do {
					obj.put("x", res.getString(1));
					obj.put("y", res.getString(2));
				} while(res.next());
			} else {
				return null;
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (JSONException e) {
			e.printStackTrace();
		}

		try {
			res.close();
			stam.close();
			conn.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return obj;
	}
	
	public List<Map<String, Integer>> getWifiListFromDatabase(List<Integer> posIds) {
		DatabaseOperator databaseOp = new DatabaseOperator();
		Connection conn = databaseOp.getConnect();
		Statement stam = null;
		Statement stam2 = null;
		try {
			stam = conn.createStatement();
			stam2 = conn.createStatement();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		String posIdStr = posIds.toString();
		posIdStr = posIdStr.substring(1, posIdStr.length() - 1);
		List<Map<String, Integer>> list = new ArrayList<Map<String, Integer>>();
		//show resultSet
		String sql = "select time_info,pos_id from finger where pos_id in (" + posIdStr + ") group by time_info";
		logger.info("Range Info Select Sql="+sql);
		ResultSet res = databaseOp.getResultSet(stam, sql);
		try {
			while(res.next()) {
				Map<String, Integer> row = new TreeMap<String, Integer>();
				String timeinfo = res.getString(1);
				sql = "select mac,rssi from finger where time_info='" + timeinfo + "'";
				ResultSet t = databaseOp.getResultSet(stam2, sql);
				while(t.next()) {
					row.put(t.getString(1), t.getInt(2));
				}
				row.put("posId", res.getInt(2));
				list.add(row);
				t.close();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		try {
			res.close();
			stam.close();
			stam2.close();
			conn.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return list;
	}

	
	
	public void printWifiList() {
		Map<String, Integer> wifiInfo;
		for(int i = 0; i < this.wifiList.size(); i++) {
			wifiInfo = this.wifiList.get(i);
			ArrayList<Map.Entry<String, Integer>> entries = sortMap(wifiInfo);
			for (int j = 0; j < entries.size(); j++) {
				System.out.print(entries.get(j).getKey() + "->" + entries.get(j).getValue() + "\t");
			}
			System.out.println("");
		}
	}
	
	public ArrayList<Map.Entry<String, Integer>> sortMap(Map<String, Integer> map) {
		List<Map.Entry<String, Integer>> arrayList = new ArrayList<Map.Entry<String, Integer>>(map.entrySet());
		Collections.sort(arrayList, new Comparator<Map.Entry<String, Integer>>() {

			@Override
			public int compare(Entry<String, Integer> o1, Entry<String, Integer> o2) {
				return o2.getValue() - o1.getValue();
			}
		});
		return (ArrayList<Entry<String, Integer>>) arrayList;
	}

	public void addWifiData(Map<String, Integer> data) {
		this.wifiList.add(data);
	}

	public String getMapId() {
		return mapId;
	}

	public void setMapId(String mapId) {
		this.mapId = mapId;
	}
	
	public void info(String str) {
		logger.info(str);
	}
	
}
