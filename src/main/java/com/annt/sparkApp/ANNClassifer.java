package com.annt.sparkApp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.Serializable;
import java.util.Iterator;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;

import scala.Tuple2;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.annt.interf.ActiveFunction;
import com.annt.layer.BasicLayer;
import com.annt.network.SimpleNetwork;
import com.annt.obj.DoubleSample;
import com.annt.obj.NetworkUpdate;
import com.annt.tranning.SimpleBackPropagation;

public class ANNClassifer implements Serializable {
	/**
	 * spark_annt.json文件用于配置神经网络 输入为JavaRDD<DoubleSample>
	 */
	private static final long serialVersionUID = -7418183431014127688L;
	// 随机数种子
	private static final Random seed = new Random();
	// SPARK集群url地址
	public static String SPARK_URL;
	// 权值衰减参数
	public static double lamda;
	// 学习率
	public static double learning_rate;
	// spark conf对象
	public static SparkConf conf;
	// spark context对象
	public static JavaSparkContext jsc;
	// 神经网络对象
	public static SimpleNetwork network;
	// 最大误差
	public static double max_error;
	// 最大训练次数
	public static int max_time;

	public static void main(String[] args) throws FileNotFoundException {
		String inputPath = "hdfs://192.168.1.140:9000/user/terry/fakeDataset";
		loadConf("spark_annt.json");
		// 读取数据集
		JavaRDD<DoubleSample> rdd = readDataset(inputPath);
		// 数据集分组
		JavaPairRDD<Integer, Iterable<DoubleSample>> groupedRDD = groupDataset(rdd);
		// 分组训练
		jsc.stop();
	}

	public static JavaRDD<DoubleSample> readDataset(String inputPath) {
		return jsc.objectFile(inputPath);
	}

	public static JavaPairRDD<Integer, Iterable<DoubleSample>> groupDataset(
			JavaRDD<DoubleSample> sampleRDD) {
		final Integer group_num = Integer.parseInt(conf
				.get("spark.default.parallelism"));
		return sampleRDD.groupBy(new Function<DoubleSample, Integer>() {
			/**
			 * 将原始输入数据分组
			 */
			private static final long serialVersionUID = 5527088255693151583L;

			public Integer call(DoubleSample v) throws Exception {
				return (Math.abs(v.hashCode() + seed.nextInt())) % group_num;
			}
		});
	}
	
	// 获得更新矩阵总和
	public static NetworkUpdate getUpdateSum(JavaRDD<NetworkUpdate> rdd){
		return rdd.reduce(new Function2<NetworkUpdate, NetworkUpdate, NetworkUpdate>() {
			/**
			 * 
			 */
			private static final long serialVersionUID = -5864282660589875617L;

			public NetworkUpdate call(NetworkUpdate v1, NetworkUpdate v2)
					throws Exception {
				v1.add(v2);
				return v1;
			}
		});
	}

	// 训练过程
	public static JavaRDD<NetworkUpdate> train(
			JavaPairRDD<Integer, Iterable<DoubleSample>> groupRDD) {
		return groupRDD
				.map(new Function<Tuple2<Integer, Iterable<DoubleSample>>, NetworkUpdate>() {

					/**
					 * 训练过程
					 */
					private static final long serialVersionUID = 4245490350518655832L;

					public NetworkUpdate call(
							Tuple2<Integer, Iterable<DoubleSample>> v)
							throws Exception {
						Iterator<DoubleSample> iterator = v._2.iterator();
						DoubleSample sample = iterator.next();
						NetworkUpdate nu = new NetworkUpdate();
						// 训练第一个样本
						SimpleBackPropagation sbp = new SimpleBackPropagation(
								network);
						if (sample != null) {
							sbp.getUpdateMatrixs(sample.input, sample.ideal);
							nu.addFirst(sbp.weights_updates, sbp.biass_updates);
						}
						while (iterator.hasNext()) {
							sample = iterator.next();
							sbp.getUpdateMatrixs(sample.input, sample.ideal);
							nu.add(sbp.weights_updates, sbp.biass_updates);
						}
						return nu;
					}
				});
	}

	// 读取spark集群和神经网络配置
	@SuppressWarnings("resource")
	public static boolean loadConf(String jsonPath) {
		String content;
		JSONObject conf_json;
		try {
			content = new Scanner(new File(jsonPath)).useDelimiter("\\Z")
					.next();
			conf_json = JSONObject.parseObject(content);
			// spark配置
			conf = new SparkConf();
			conf.setAppName(conf_json.getString("SPARK_APPNAME"));
			SPARK_URL = conf_json.getString("SPARK_URL");
			conf.setMaster(SPARK_URL);
			JSONObject spark_base_conf = conf_json
					.getJSONObject("SPARK_BASE_CONF");
			Set<String> keys = spark_base_conf.keySet();
			for (String key : keys) {
				conf.set(key, spark_base_conf.getString(key));
			}
			// 添加jar包
			JSONArray jars_array = conf_json.getJSONArray("jars");
			String[] jars_conf = new String[jars_array.size()];
			for (int i = 0; i < jars_conf.length; i++) {
				jars_conf[i] = jars_array.getString(i);
			}
			conf.setJars(jars_conf);
			jsc = new JavaSparkContext(conf);
			// 神经网络配置
			JSONObject annt_conf_json = conf_json.getJSONObject("ANN_CONF");
			JSONArray cell_array = annt_conf_json.getJSONArray("cell");
			JSONArray bias_array = annt_conf_json.getJSONArray("bias");
			JSONArray active_array = annt_conf_json.getJSONArray("active");
			double weight_ratio = annt_conf_json.getDouble("weight_ratio");
			network = new SimpleNetwork();
			for (int i = 0; i < cell_array.size(); i++) {
				// 反射获得激活函数
				Class<?> c = Class.forName(active_array.getString(i));
				ActiveFunction func = (ActiveFunction) c.newInstance();
				network.addLayer(new BasicLayer(cell_array.getIntValue(i),
						bias_array.getBooleanValue(i), func));
			}
			network.initNetwork(weight_ratio);
			// 学习率
			learning_rate = conf_json.getDoubleValue("learning_rate");
			// 权值衰减参数
			lamda = conf_json.getDoubleValue("lamda");
			// 最大误差
			max_error = conf_json.getDoubleValue("max_error");
			// 最多训练次数
			max_time = conf_json.getIntValue("max_time");
		} catch (FileNotFoundException e) {
			System.err.println("json配置文件不存在");
			return false;
		} catch (JSONException e) {
			System.err.println("JSON解析错误...");
			return false;
		} catch (ClassNotFoundException e) {
			System.err.println("未定义激活函数");
			return false;
		} catch (InstantiationException e) {
			System.err.println("激活函数无构造函数");
			return false;
		} catch (IllegalAccessException e) {
			System.err.println("激活函数权限出错");
			return false;
		}
		return true;
	}
}
