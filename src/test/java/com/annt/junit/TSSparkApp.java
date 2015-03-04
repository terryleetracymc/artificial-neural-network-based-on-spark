package com.annt.junit;

import org.jblas.DoubleMatrix;
import org.junit.Test;

import com.annt.network.RBMNetwork;
import com.annt.network.SimpleNetwork;

public class TSSparkApp {

	@Test
	public void GenerateBestL1InitNetwork() {
		RBMNetwork rbm = RBMNetwork.loadNetwork("best/rbm_250_200.nt");
		SimpleNetwork firstNetwork = rbm.getNetwork();
		SimpleNetwork secondNetwork = rbm.getRNetwork();
		firstNetwork.addUpperNetwork(secondNetwork);
		System.out.println(firstNetwork.weights.getLast());
		SimpleNetwork.saveNetwork("network/250_200_250.nt", firstNetwork);
	}

	// @Test
	public void SeeRestoreSign() {
		System.out.println(DoubleMatrix.randn(10));
	}
}
