package org.deeplearning4j.nn.multilayer;


import org.apache.commons.lang3.ArrayUtils;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.datasets.iterator.DataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.*;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.OutputPreProcessor;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.layers.OutputLayer;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.solvers.StochasticHessianFree;
import org.deeplearning4j.util.MultiLayerUtil;
import org.nd4j.linalg.api.activation.ActivationFunction;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.linalg.sampling.Sampling;
import org.nd4j.linalg.transformation.MatrixTransform;
import org.nd4j.linalg.util.FeatureUtil;
import org.nd4j.linalg.util.LinAlgExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;


/**
 * A base class for a multi layer neural network with a logistic output layer
 * and multiple hidden neuralNets.
 *
 * @author Adam Gibson
 */
public class MultiLayerNetwork implements Serializable, Classifier {


  private static final Logger log = LoggerFactory.getLogger(MultiLayerNetwork.class);
  private static final long serialVersionUID = -5029161847383716484L;
  //the hidden neuralNets
  protected Layer[] layers;


  //default training examples and associated neuralNets
  protected INDArray input, labels;
  //sometimes we may need to transform weights; this allows a
  //weight transform upon layer setup
  protected Map<Integer, MatrixTransform> weightTransforms = new HashMap<>();
  //hidden bias transforms; for initialization
  protected Map<Integer, MatrixTransform> hiddenBiasTransforms = new HashMap<>();
  //visible bias transforms for initialization
  protected Map<Integer, MatrixTransform> visibleBiasTransforms = new HashMap<>();
  protected boolean initCalled = false;


  protected NeuralNetConfiguration defaultConfiguration;
  protected MultiLayerConfiguration layerWiseConfigurations;


  /*
    Binary drop connect mask
   */
  protected INDArray mask;


  public MultiLayerNetwork(MultiLayerConfiguration conf) {
    this.layerWiseConfigurations = conf;
    this.defaultConfiguration = conf.getConf(0);
  }

  /**
   * Initialize the network based on the configuration
   *
   * @param conf   the configuration json
   * @param params the parameters
   */
  public MultiLayerNetwork(String conf, INDArray params) {
    this(MultiLayerConfiguration.fromJson(conf));
    init();
    setParameters(params);
  }


  /**
   * Initialize the network based on the configuraiton
   *
   * @param conf   the configuration
   * @param params the parameters
   */
  public MultiLayerNetwork(MultiLayerConfiguration conf, INDArray params) {
    this(conf);
    init();
    setParameters(params);
  }


  protected void intializeConfigurations() {

    if (layerWiseConfigurations == null)
      layerWiseConfigurations = new MultiLayerConfiguration.Builder().build();

    if (layers == null)
      layers = new Layer[getnLayers() + 1];

    if (defaultConfiguration == null)
      defaultConfiguration = new NeuralNetConfiguration.Builder()
          .build();

    //add a default configuration for each hidden layer + output layer
    if (layerWiseConfigurations == null || layerWiseConfigurations.getConfs().isEmpty())
      for (int i = 0; i < layerWiseConfigurations.getHiddenLayerSizes().length + 1; i++) {
        layerWiseConfigurations.getConfs().add(defaultConfiguration.clone());
      }


  }


  /**
   * This unsupervised learning method runs
   * contrastive divergence on each RBM layer in the network.
   *
   * @param iter the input to iterate on
   *             The typical tip is that the higher k is the closer to the model
   *             you will be approximating due to more sampling. K = 1
   *             usually gives very good results and is the default in quite a few situations.
   */
  public void pretrain(DataSetIterator iter) {
    if (!layerWiseConfigurations.isPretrain())
      return;

    INDArray layerInput;

    for (int i = 0; i < getnLayers(); i++) {
      if (i == 0) {
        while (iter.hasNext()) {
          DataSet next = iter.next();
          this.input = next.getFeatureMatrix();
                      /*During pretrain, feed forward expected activations of network, use activation cooccurrences during pretrain  */
          if (this.getInput() == null || this.getLayers() == null) {
            setInput(input);
            initializeLayers(input);
          } else
            setInput(input);
          getLayers()[i].fit(next.getFeatureMatrix());
          log.info("Training on layer " + (i + 1) + " with " + input.slices() + " examples");


        }

        iter.reset();
      } else {
        while (iter.hasNext()) {
          DataSet next = iter.next();
          layerInput = next.getFeatureMatrix();
          for (int j = 1; j <= i; j++)
            layerInput = activationFromPrevLayer(j - 1, layerInput);

          log.info("Training on layer " + (i + 1) + " with " + layerInput.slices() + " examples");
          getLayers()[i].fit(layerInput);

        }

        iter.reset();


      }
    }
  }


  /**
   * This unsupervised learning method runs
   * contrastive divergence on each RBM layer in the network.
   *
   * @param input the input to iterate on
   *              The typical tip is that the higher k is the closer to the model
   *              you will be approximating due to more sampling. K = 1
   *              usually gives very good results and is the default in quite a few situations.
   */
  public void pretrain(INDArray input) {

    if (!layerWiseConfigurations.isPretrain())
      return;
        /* During pretrain, feed forward expected activations of network, use activation cooccurrences during pretrain  */
    if (this.getInput() == null || this.getLayers() == null) {
      setInput(input);
      initializeLayers(input);
    } else
      setInput(input);

    INDArray layerInput = null;

    for (int i = 0; i < getnLayers() - 1; i++) {
      if (i == 0)
        layerInput = getInput();
      else
        layerInput = activationFromPrevLayer(i - 1, layerInput);
      log.info("Training on layer " + (i + 1) + " with " + layerInput.slices() + " examples");
      getLayers()[i].fit(layerInput);


    }
  }


  @Override
  public int batchSize() {
    return input.slices();
  }

  @Override
  public NeuralNetConfiguration conf() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setConf(NeuralNetConfiguration conf) {
    throw new UnsupportedOperationException();
  }

  @Override
  public INDArray input() {
    return input;
  }

  @Override
  public void validateInput() {

  }

  @Override
  public ConvexOptimizer getOptimizer() {
    return null;
  }

  @Override
  public INDArray getParam(String param) {
    return null;
  }

  @Override
  public void initParams() {

  }

  @Override
  public Map<String, INDArray> paramTable() {
    return null;
  }

  @Override
  public void setParamTable(Map<String, INDArray> paramTable) {

  }

  @Override
  public void setParam(String key, INDArray val) {

  }

  /**
   * Transform the data based on the model's output.
   * This can be anything from a number to reconstructions.
   *
   * @param data the data to transform
   * @return the transformed data
   */
  @Override
  public INDArray transform(INDArray data) {
    return output(data);
  }


  public NeuralNetConfiguration getDefaultConfiguration() {
    return defaultConfiguration;
  }

  public void setDefaultConfiguration(NeuralNetConfiguration defaultConfiguration) {
    this.defaultConfiguration = defaultConfiguration;
  }

  public MultiLayerConfiguration getLayerWiseConfigurations() {
    return layerWiseConfigurations;
  }

  public void setLayerWiseConfigurations(MultiLayerConfiguration layerWiseConfigurations) {
    this.layerWiseConfigurations = layerWiseConfigurations;
  }

  /**
   * Base class for initializing the neuralNets based on the input.
   * This is meant for capturing numbers such as input columns or other things.
   *
   * @param input the input matrix for training
   */
  public void initializeLayers(INDArray input) {
    if (input == null)
      throw new IllegalArgumentException("Unable to initialize neuralNets with empty input");
    int[] hiddenLayerSizes = getLayerWiseConfigurations().getHiddenLayerSizes();
    if (input.shape().length == 2)
      for (int i = 0; i < hiddenLayerSizes.length; i++)
        if (hiddenLayerSizes[i] < 1)
          throw new IllegalArgumentException("All hidden layer sizes must be >= 1");


    this.input = input.dup();
    if (!initCalled) {
      init();
      //log.info("Initializing neuralNets with input of dims " + input.slices() + " x " + input.columns());

    }


  }

  public void init() {
    if (layerWiseConfigurations == null || layers == null)
      intializeConfigurations();


    INDArray layerInput = input;
    int inputSize;
    if (getnLayers() < 1)
      throw new IllegalStateException("Unable to createComplex network neuralNets; number specified is less than 1");

    int[] hiddenLayerSizes = layerWiseConfigurations.getHiddenLayerSizes();
    if (this.layers == null || this.layers[0] == null) {
      //
      this.layers = new Layer[hiddenLayerSizes.length + 1];
      // construct multi-layer
      for (int i = 0; i < this.getnLayers(); i++) {

        if (i == 0)
          inputSize = layerWiseConfigurations.getConf(0).getnIn();
        else
          inputSize = hiddenLayerSizes[i - 1];

        if (i == 0) {
          layerWiseConfigurations.getConf(i).setnIn(inputSize);
          layerWiseConfigurations.getConf(i).setnOut(hiddenLayerSizes[i]);
          // construct sigmoid_layer
          layers[i] = layerWiseConfigurations.getConf(i).getLayerFactory().create(layerWiseConfigurations.getConf(i));
        } else if (i < getLayers().length - 1) {
          if (input != null)
            layerInput = activationFromPrevLayer(i - 1, layerInput);

          layerWiseConfigurations.getConf(i).setnIn(inputSize);
          layerWiseConfigurations.getConf(i).setnOut(hiddenLayerSizes[i]);
          layers[i] = layerWiseConfigurations.getConf(i).getLayerFactory().create(layerWiseConfigurations.getConf(i));

        }


      }


      NeuralNetConfiguration last = layerWiseConfigurations.getConf(layerWiseConfigurations.getConfs().size() - 1);
      NeuralNetConfiguration secondToLast = layerWiseConfigurations.getConf(layerWiseConfigurations.getConfs().size() - 2);
      last.setnIn(secondToLast.getnOut());


      this.layers[layers.length - 1] = last.getLayerFactory().create(last);

      initCalled = true;
      initMask();


    }


  }


  /**
   * Triggers the activation of the last hidden layer ie: not logistic regression
   *
   * @return the activation of the last hidden layer given the last input to the network
   */
  public INDArray activate() {
    return getLayers()[getLayers().length - 1].activate();
  }

  /**
   * Triggers the activation for a given layer
   *
   * @param layer the layer to activate on
   * @return the activation for a given layer
   */
  public INDArray activate(int layer) {
    return getLayers()[layer].activate();
  }

  /**
   * Triggers the activation of the given layer
   *
   * @param layer the layer to trigger on
   * @param input the input to the hidden layer
   * @return the activation of the layer based on the input
   */
  public INDArray activate(int layer, INDArray input) {
    return getLayers()[layer].activate(input);
  }


  /**
   * Sets the input and labels from this dataset
   *
   * @param data the dataset to initialize with
   */
  public void initialize(DataSet data) {
    setInput(data.getFeatureMatrix());
    feedForward(data.getFeatureMatrix());
    this.labels = data.getLabels();
    if (getOutputLayer() instanceof OutputLayer) {
      OutputLayer o = (OutputLayer) getOutputLayer();
      o.setLabels(labels);

    }
  }

  /**
   * Calculate activation from previous layer including pre processing where necessary
   *
   * @param curr  the current layer
   * @param input the input
   * @return the activation from the previous layer
   */
  public INDArray activationFromPrevLayer(int curr, INDArray input) {
    INDArray ret = layers[curr].activate(input);
    if (getLayerWiseConfigurations().getProcessors() != null && getLayerWiseConfigurations().getPreProcessor(curr) != null) {
      ret = getLayerWiseConfigurations().getPreProcessor(curr).preProcess(ret);
      return ret;
    }
    return ret;
  }

  /**
   * Compute activations from input to output of the output layer
   *
   * @return the list of activations for each layer
   */
  public List<INDArray> feedForward() {
    INDArray currInput = this.input;
    if (this.input.isMatrix() && this.input.columns() != defaultConfiguration.getnIn())
      throw new IllegalStateException("Illegal input length");

    List<INDArray> activations = new ArrayList<>();
    activations.add(currInput);

    for (int i = 0; i < layers.length; i++) {
      currInput = activationFromPrevLayer(i, currInput);
      //pre process the activation before passing to the next layer
      OutputPreProcessor preProcessor = getLayerWiseConfigurations().getPreProcessor(i);
      if (preProcessor != null)
        currInput = preProcessor.preProcess(currInput);
      //applies drop connect to the activation
      applyDropConnectIfNecessary(currInput);
      activations.add(currInput);
    }


    return activations;
  }

  /**
   * Compute activations from input to output of the output layer
   *
   * @return the list of activations for each layer
   */
  public List<INDArray> feedForward(INDArray input) {
    if (input == null)
      throw new IllegalStateException("Unable to perform feed forward; no input found");

    else
      this.input = input;
    return feedForward();
  }

  @Override
  public Gradient gradient() {
    Gradient ret = new DefaultGradient();
    for (int i = 0; i < layers.length; i += 2) {
      ret.gradientForVariable().put(String.valueOf(i), layers[i].gradient().gradient());
    }

    return ret;
  }

  @Override
  public Pair<Gradient, Double> gradientAndScore() {
    return new Pair<>(gradient(), getOutputLayer().score());
  }

  /**
   * Applies drop connect relative to connections.
   * This should be used on the activation of a neural net. (Post sigmoid layer)
   *
   * @param input the input to apply drop connect to
   */
  protected void applyDropConnectIfNecessary(INDArray input) {
    if (layerWiseConfigurations.isUseDropConnect()) {
      INDArray mask = Sampling.binomial(Nd4j.valueArrayOf(input.slices(), input.columns(), 0.5), 1, defaultConfiguration.getRng());
      input.muli(mask);
      //apply l2 for drop connect
      if (defaultConfiguration.getL2() > 0)
        input.muli(defaultConfiguration.getL2());
    }
  }


  /* delta computation for back prop with the R operator */
  protected List<INDArray> computeDeltasR(INDArray v) {
    List<INDArray> deltaRet = new ArrayList<>();

    INDArray[] deltas = new INDArray[getnLayers() + 1];
    List<INDArray> activations = feedForward();
    List<INDArray> rActivations = feedForwardR(activations, v);
      /*
     * Precompute activations and z's (pre activation network outputs)
		 */
    List<INDArray> weights = new ArrayList<>();
    List<INDArray> biases = new ArrayList<>();
    List<ActivationFunction> activationFunctions = new ArrayList<>();


    for (int j = 0; j < getLayers().length; j++) {
      weights.add(getLayers()[j].getParam(DefaultParamInitializer.WEIGHT_KEY));
      biases.add(getLayers()[j].getParam(DefaultParamInitializer.BIAS_KEY));
      activationFunctions.add(getLayers()[j].conf().getActivationFunction());
    }


    INDArray rix = rActivations.get(rActivations.size() - 1).divi((double) input.slices());
    LinAlgExceptions.assertValidNum(rix);

    //errors
    for (int i = getnLayers() - 1; i >= 0; i--) {
      //W^t * error^l + 1
      deltas[i] = activations.get(i).transpose().mmul(rix);
      applyDropConnectIfNecessary(deltas[i]);

      if (i > 0)
        rix = rix.mmul(weights.get(i).addRowVector(biases.get(i)).transpose()).muli(activationFunctions.get(i - 1).applyDerivative(activations.get(i)));


    }

    for (int i = 0; i < deltas.length - 1; i++) {
      if (defaultConfiguration.isConstrainGradientToUnitNorm()) {
        double sum = deltas[i].sum(Integer.MAX_VALUE).getDouble(0);
        if (sum > 0)
          deltaRet.add(deltas[i].div(deltas[i].norm2(Integer.MAX_VALUE)));
        else
          deltaRet.add(deltas[i]);
      } else
        deltaRet.add(deltas[i]);
      LinAlgExceptions.assertValidNum(deltaRet.get(i));
    }

    return deltaRet;
  }


  //damping update after line search
  public void dampingUpdate(double rho, double boost, double decrease) {
    if (rho < 0.25 || Double.isNaN(rho))
      layerWiseConfigurations.setDampingFactor(getLayerWiseConfigurations().getDampingFactor() * boost);


    else if (rho > 0.75)
      layerWiseConfigurations.setDampingFactor(getLayerWiseConfigurations().getDampingFactor() * decrease);
  }

  /* p and gradient are same length */
  public double reductionRatio(INDArray p, double currScore, double score, INDArray gradient) {
    double currentDamp = layerWiseConfigurations.getDampingFactor();
    layerWiseConfigurations.setDampingFactor(0);
    INDArray denom = getBackPropRGradient(p);
    denom.muli(0.5).muli(p.mul(denom)).sum(0);
    denom.subi(gradient.mul(p).sum(0));
    double rho = (currScore - score) / (double) denom.getScalar(0).element();
    layerWiseConfigurations.setDampingFactor(currentDamp);
    if (score - currScore > 0)
      return Float.NEGATIVE_INFINITY;
    return rho;
  }


  /* delta computation for back prop with precon for SFH */
  protected List<Pair<INDArray, INDArray>> computeDeltas2() {
    List<Pair<INDArray, INDArray>> deltaRet = new ArrayList<>();
    List<INDArray> activations = feedForward();
    INDArray[] deltas = new INDArray[activations.size() - 1];
    INDArray[] preCons = new INDArray[activations.size() - 1];


    //- y - h
    INDArray ix = activations.get(activations.size() - 1).sub(labels).div(labels.slices());

       	/*
		 * Precompute activations and z's (pre activation network outputs)
		 */
    List<INDArray> weights = new ArrayList<>();
    List<INDArray> biases = new ArrayList<>();

    List<ActivationFunction> activationFunctions = new ArrayList<>();
    for (int j = 0; j < getLayers().length; j++) {
      weights.add(getLayers()[j].getParam(DefaultParamInitializer.WEIGHT_KEY));
      biases.add(getLayers()[j].getParam(DefaultParamInitializer.BIAS_KEY));
      activationFunctions.add(getLayers()[j].conf().getActivationFunction());
    }


    //errors
    for (int i = weights.size() - 1; i >= 0; i--) {
      deltas[i] = activations.get(i).transpose().mmul(ix);
      preCons[i] = Transforms.pow(activations.get(i).transpose(), 2).mmul(Transforms.pow(ix, 2)).muli(labels.slices());
      applyDropConnectIfNecessary(deltas[i]);

      if (i > 0) {
        //W[i] + b[i] * f'(z[i - 1])
        ix = ix.mmul(weights.get(i).transpose()).muli(activationFunctions.get(i - 1).applyDerivative(activations.get(i)));
      }
    }

    for (int i = 0; i < deltas.length; i++) {
      if (defaultConfiguration.isConstrainGradientToUnitNorm())
        deltaRet.add(new Pair<>(deltas[i].divi(deltas[i].norm2(Integer.MAX_VALUE)), preCons[i]));

      else
        deltaRet.add(new Pair<>(deltas[i], preCons[i]));

    }

    return deltaRet;
  }


  /* delta computation for back prop */
  protected List<INDArray> computeDeltas() {
    List<INDArray> deltaRet = new ArrayList<>();
    INDArray[] deltas = new INDArray[getnLayers() + 2];

    List<INDArray> activations = feedForward();

    //- y - h
    INDArray ix = labels.sub(activations.get(activations.size() - 1)).subi(getOutputLayer().conf().getActivationFunction().applyDerivative(activations.get(activations.size() - 1)));

		/*
		 * Precompute activations and z's (pre activation network outputs)
		 */
    List<INDArray> weights = new ArrayList<>();
    List<INDArray> biases = new ArrayList<>();

    List<ActivationFunction> activationFunctions = new ArrayList<>();
    for (int j = 0; j < getLayers().length; j++) {
      weights.add(getLayers()[j].getParam(DefaultParamInitializer.WEIGHT_KEY));
      biases.add(getLayers()[j].getParam(DefaultParamInitializer.BIAS_KEY));
      activationFunctions.add(getLayers()[j].conf().getActivationFunction());
    }


    weights.add(getOutputLayer().getParam(DefaultParamInitializer.WEIGHT_KEY));
    biases.add(getOutputLayer().getParam(DefaultParamInitializer.BIAS_KEY));
    activationFunctions.add(getOutputLayer().conf().getActivationFunction());


    //errors
    for (int i = getnLayers() + 1; i >= 0; i--) {
      //output layer
      if (i >= getnLayers() + 1) {
        //-( y - h) .* f'(z^l) where l is the output layer
        deltas[i] = ix;

      } else {
        INDArray delta = activations.get(i).transpose().mmul(ix);
        deltas[i] = delta;
        applyDropConnectIfNecessary(deltas[i]);
        INDArray weightsPlusBias = weights.get(i).transpose();
        INDArray activation = activations.get(i);
        if (i > 0)
          ix = ix.mmul(weightsPlusBias).muli(activationFunctions.get(i - 1).applyDerivative(activation));

      }

    }

    for (int i = 0; i < deltas.length; i++) {
      if (defaultConfiguration.isConstrainGradientToUnitNorm())
        deltaRet.add(deltas[i].divi(deltas[i].norm2(Integer.MAX_VALUE)));

      else
        deltaRet.add(deltas[i]);
    }


    return deltaRet;
  }

  /**
   * One step of back prop
   */
  public void backPropStep() {
    List<Pair<INDArray, INDArray>> deltas = backPropGradient();
    for (int i = 0; i < layers.length; i++) {
      layers[i].getParam(DefaultParamInitializer.WEIGHT_KEY).addi(deltas.get(i).getFirst());
      layers[i].getParam(DefaultParamInitializer.BIAS_KEY).addi(deltas.get(i).getSecond());

    }

  }


  /**
   * Gets the back prop gradient with the r operator (gauss vector)
   * This is also called computeGV
   *
   * @param v the v in gaussian newton vector g * v
   * @return the back prop with r gradient
   */
  public INDArray getBackPropRGradient(INDArray v) {
    return pack(backPropGradientR(v));
  }


  /**
   * Gets the back prop gradient with the r operator (gauss vector)
   * and the associated precon matrix
   * This is also called computeGV
   *
   * @return the back prop with r gradient
   */
  public Pair<INDArray, INDArray> getBackPropGradient2() {
    List<Pair<Pair<INDArray, INDArray>, Pair<INDArray, INDArray>>> deltas = backPropGradient2();
    List<Pair<INDArray, INDArray>> deltaNormal = new ArrayList<>();
    List<Pair<INDArray, INDArray>> deltasPreCon = new ArrayList<>();
    for (int i = 0; i < deltas.size(); i++) {
      deltaNormal.add(deltas.get(i).getFirst());
      deltasPreCon.add(deltas.get(i).getSecond());
    }


    return new Pair<>(pack(deltaNormal), pack(deltasPreCon));
  }


  @Override
  public MultiLayerNetwork clone() {
    MultiLayerNetwork ret;
    try {
      ret = getClass().newInstance();
      ret.update(this);

    } catch (Exception e) {
      throw new IllegalStateException("Unable to cloe network");
    }
    return ret;
  }


  /**
   * Returns a 1 x m vector where the vector is composed of
   * a flattened vector of all of the weights for the
   * various neuralNets(w,hbias NOT VBIAS) and output layer
   *
   * @return the params for this neural net
   */
  @Override
  public INDArray params() {
    List<INDArray> params = new ArrayList<>();
    for (int i = 0; i < getnLayers(); i++)
      params.add(layers[i].params());

    return Nd4j.toFlattened(params);
  }

  /**
   * Set the parameters for this model.
   * This expects a linear ndarray which then be unpacked internally
   * relative to the expected ordering of the model
   *
   * @param params the parameters for the model
   */
  @Override
  public void setParams(INDArray params) {
    setParameters(params);

  }


  /**
   * Returns a 1 x m vector where the vector is composed of
   * a flattened vector of all of the weights for the
   * various neuralNets and output layer
   *
   * @return the params for this neural net
   */
  @Override
  public int numParams() {
    int length = 0;
    for (int i = 0; i < layers.length; i++)
      length += layers[i].numParams();

    return length;

  }

  /**
   * Packs a set of matrices in to one vector,
   * where the matrices in this case are the w,hbias at each layer
   * and the output layer w,bias
   *
   * @return a singular matrix of all of the neuralNets packed in to one matrix
   */

  public INDArray pack() {
    return params();

  }

  /**
   * Packs a set of matrices in to one vector
   *
   * @param layers the neuralNets to pack
   * @return a singular matrix of all of the neuralNets packed in to one matrix
   */
  public INDArray pack(List<Pair<INDArray, INDArray>> layers) {
    List<INDArray> list = new ArrayList<>();

    for (Pair<INDArray, INDArray> layer : layers) {
      list.add(layer.getFirst());
      list.add(layer.getSecond());
    }
    return Nd4j.toFlattened(list);
  }


  /**
   * Sets the input and labels and returns a score for the prediction
   * wrt true labels
   *
   * @param data the data to score
   * @return the score for the given input,label pairs
   */
  @Override
  public double score(org.nd4j.linalg.dataset.api.DataSet data) {
    return score(data.getFeatureMatrix(), data.getLabels());
  }

  /**
   * Do a back prop iteration.
   * This involves computing the activations, tracking the last neuralNets weights
   * to revert to in case of convergence, the learning rate being used to iterate
   * and the current epoch
   *
   * @return whether the training should converge or not
   */
  public List<Pair<INDArray, INDArray>> backPropGradient() {
    //feedforward to compute activations
    //initial error

    //precompute deltas
    List<INDArray> deltas = computeDeltas();

    List<Pair<INDArray, INDArray>> vWvB = new ArrayList<>();
    for (Layer layer : layers)
      vWvB.add(new Pair<>(layer.getParam(DefaultParamInitializer.WEIGHT_KEY), layer.getParam(DefaultParamInitializer.BIAS_KEY)));


    List<Pair<INDArray, INDArray>> list = new ArrayList<>();

    for (int l = 0; l < getnLayers() + 1; l++) {
      INDArray gradientChange = deltas.get(l);
      if (gradientChange.length() != getLayers()[l].getParam(DefaultParamInitializer.WEIGHT_KEY).length())
        throw new IllegalStateException("Gradient change not equal to weight change");


      //update hidden bias
      INDArray deltaColumnSums = deltas.get(l).isVector() ? deltas.get(l) : deltas.get(l).mean(0);


      list.add(new Pair<>(gradientChange, deltaColumnSums));


    }


    if (mask == null)
      initMask();


    return list;

  }


  /**
   * Unpacks a parameter matrix in to a
   * transform of pairs(w,hbias)
   * triples with layer wise
   *
   * @param param the param vector
   * @return a segmented list of the param vector
   */
  public List<Pair<INDArray, INDArray>> unPack(INDArray param) {
    //more sanity checks!
    if (param.slices() != 1)
      param = param.reshape(1, param.length());
    List<Pair<INDArray, INDArray>> ret = new ArrayList<>();
    int curr = 0;
    for (int i = 0; i < layers.length; i++) {
      int layerLength = layers[i].getParam(DefaultParamInitializer.WEIGHT_KEY).length() + layers[i].getParam(DefaultParamInitializer.BIAS_KEY).length();
      INDArray subMatrix = param.get(NDArrayIndex.interval(curr, curr + layerLength));
      INDArray weightPortion = subMatrix.get(NDArrayIndex.interval(0, layers[i].getParam(DefaultParamInitializer.WEIGHT_KEY).length()));

      int beginHBias = layers[i].getParam(DefaultParamInitializer.WEIGHT_KEY).length();
      int endHbias = subMatrix.length();
      INDArray hBiasPortion = subMatrix.get(NDArrayIndex.interval(beginHBias, endHbias));
      int layerLengthSum = weightPortion.length() + hBiasPortion.length();
      if (layerLengthSum != layerLength) {
        if (hBiasPortion.length() != layers[i].getParam(DefaultParamInitializer.BIAS_KEY).length())
          throw new IllegalStateException("Hidden bias on layer " + i + " was off");
        if (weightPortion.length() != layers[i].getParam(DefaultParamInitializer.WEIGHT_KEY).length())
          throw new IllegalStateException("Weight portion on layer " + i + " was off");

      }

      ret.add(new Pair<>(weightPortion.reshape(layers[i].getParam(DefaultParamInitializer.WEIGHT_KEY).slices(), layers[i].getParam(DefaultParamInitializer.WEIGHT_KEY).columns()), hBiasPortion.reshape(layers[i].getParam(DefaultParamInitializer.BIAS_KEY).slices(), layers[i].getParam(DefaultParamInitializer.BIAS_KEY).columns())));
      curr += layerLength;
    }


    return ret;
  }

  /**
   * Do a back prop iteration.
   * This involves computing the activations, tracking the last neuralNets weights
   * to revert to in case of convergence, the learning rate being used to iterate
   * and the current epoch
   *
   * @return whether the training should converge or not
   */
  protected List<Pair<Pair<INDArray, INDArray>, Pair<INDArray, INDArray>>> backPropGradient2() {
    //feedforward to compute activations
    //initial error

    //precompute deltas
    List<Pair<INDArray, INDArray>> deltas = computeDeltas2();


    List<Pair<Pair<INDArray, INDArray>, Pair<INDArray, INDArray>>> list = new ArrayList<>();
    List<Pair<INDArray, INDArray>> grad = new ArrayList<>();
    List<Pair<INDArray, INDArray>> preCon = new ArrayList<>();

    for (int l = 0; l < deltas.size(); l++) {
      INDArray gradientChange = deltas.get(l).getFirst();
      INDArray preConGradientChange = deltas.get(l).getSecond();


      if (l < layers.length && gradientChange.length() != layers[l].getParam(DefaultParamInitializer.WEIGHT_KEY).length())
        throw new IllegalStateException("Gradient change not equal to weight change");

      //update hidden bias
      INDArray deltaColumnSums = deltas.get(l).getFirst().mean(0);
      INDArray preConColumnSums = deltas.get(l).getSecond().mean(0);

      grad.add(new Pair<>(gradientChange, deltaColumnSums));
      preCon.add(new Pair<>(preConGradientChange, preConColumnSums));
      if (l < layers.length && deltaColumnSums.length() != layers[l].getParam(DefaultParamInitializer.BIAS_KEY).length())
        throw new IllegalStateException("Bias change not equal to weight change");
      else if (l == getLayers().length && deltaColumnSums.length() != getOutputLayer().getParam(DefaultParamInitializer.BIAS_KEY).length())
        throw new IllegalStateException("Bias change not equal to weight change");


    }

    INDArray g = pack(grad);
    INDArray con = pack(preCon);
    INDArray theta = params();


    if (mask == null)
      initMask();

    g.addi(theta.mul(defaultConfiguration.getL2()).muli(mask));

    INDArray conAdd = Transforms.pow(mask.mul(defaultConfiguration.getL2()).add(Nd4j.valueArrayOf(g.slices(), g.columns(), layerWiseConfigurations.getDampingFactor())), 3.0 / 4.0);

    con.addi(conAdd);

    List<Pair<INDArray, INDArray>> gUnpacked = unPack(g);

    List<Pair<INDArray, INDArray>> conUnpacked = unPack(con);

    for (int i = 0; i < gUnpacked.size(); i++)
      list.add(new Pair<>(gUnpacked.get(i), conUnpacked.get(i)));


    return list;

  }


  @Override
  public void fit(DataSetIterator iter) {
    if (!layerWiseConfigurations.isBackward()) {
      pretrain(iter);
      iter.reset();
      finetune(iter);
    } else {
      //start at the output layer
      INDArray propagate = layers[layers.length - 1].activate();
      for (int i = layers.length - 2; i > 0; i--) {
        //back propagate the layer
        layers[i].backWard(propagate);
        //get the previous layers activation
        propagate = layers[i].activate();
      }
    }

  }

  /**
   * Run SGD based on the given labels
   *
   * @param iter fine tune based on the labels
   */
  public void finetune(DataSetIterator iter) {
    iter.reset();

    while (iter.hasNext()) {
      DataSet data = iter.next();
      if (data.getFeatureMatrix() == null || data.getLabels() == null)
        break;

      setInput(data.getFeatureMatrix());
      setLabels(data.getLabels());
      if (getOutputLayer().conf().getOptimizationAlgo() != OptimizationAlgorithm.HESSIAN_FREE) {
        feedForward();
        if (getOutputLayer() instanceof OutputLayer) {
          OutputLayer o = (OutputLayer) getOutputLayer();
          o.fit();

        }
      } else {
        StochasticHessianFree hessianFree =
            new StochasticHessianFree(getOutputLayer().conf(), getOutputLayer().conf().getStepFunction(),
                getOutputLayer().conf().getListeners(), this);
        hessianFree.optimize();
      }

    }


  }


  /**
   * Run SGD based on the given labels
   *
   * @param labels the labels to use
   */
  public void finetune(INDArray labels) {
    if (labels != null)
      this.labels = labels;
    if (!(getOutputLayer() instanceof OutputLayer)) {
      log.warn("Output layer not instance of output layer returning.");
      return;
    }
    OutputLayer o = (OutputLayer) getOutputLayer();
    if (getOutputLayer().conf().getOptimizationAlgo() != OptimizationAlgorithm.HESSIAN_FREE) {
      feedForward();
      o.fit(getOutputLayer().getInput(), labels);
    } else {
      feedForward();
      o.setLabels(labels);
      StochasticHessianFree hessianFree = new StochasticHessianFree(getOutputLayer().conf(),
          getOutputLayer().conf().getStepFunction(), getOutputLayer().conf().getListeners(), this);
      hessianFree.optimize();
    }
  }


  /**
   * Returns the predictions for each example in the dataset
   *
   * @param d the matrix to predict
   * @return the prediction for the dataset
   */
  @Override
  public int[] predict(INDArray d) {
    INDArray output = output(d);
    int[] ret = new int[d.slices()];
    for (int i = 0; i < ret.length; i++)
      ret[i] = Nd4j.getBlasWrapper().iamax(output.getRow(i));
    return ret;
  }

  /**
   * Returns the probabilities for each label
   * for each example row wise
   *
   * @param examples the examples to classify (one example in each row)
   * @return the likelihoods of each example and each label
   */
  @Override
  public INDArray labelProbabilities(INDArray examples) {
    List<INDArray> feed = feedForward(examples);
    OutputLayer o = (OutputLayer) getOutputLayer();
    return o.labelProbabilities(feed.get(feed.size() - 1));
  }

  /**
   * Fit the model
   *
   * @param examples the examples to classify (one example in each row)
   * @param labels   the example labels(a binary outcome matrix)
   */
  @Override
  public void fit(INDArray examples, INDArray labels) {
    if (!layerWiseConfigurations.isBackward()) {
      pretrain(examples);
      finetune(labels);
    } else {
      setInput(examples);
      feedForward();
      //start at the output layer
      INDArray propagate = layers[layers.length - 1].activate();
      for (int i = layers.length - 2; i > 0; i--) {
        //back propagate the layer
        layers[i].backWard(propagate);
        //get the previous layers activation
        propagate = layers[i].activate();
      }
    }
  }


  @Override
  public void fit(INDArray data) {
    pretrain(data);
  }

  @Override
  public void iterate(INDArray input) {
    pretrain(input);
  }


  /**
   * Fit the model
   *
   * @param data the data to train on
   */
  @Override
  public void fit(org.nd4j.linalg.dataset.api.DataSet data) {
    fit(data.getFeatureMatrix(), data.getLabels());
  }

  /**
   * Fit the model
   *
   * @param examples the examples to classify (one example in each row)
   * @param labels   the labels for each example (the number of labels must match
   */
  @Override
  public void fit(INDArray examples, int[] labels) {
    fit(examples, FeatureUtil.toOutcomeMatrix(labels, getOutputLayer().conf().getnOut()));
  }

  /**
   * Label the probabilities of the input
   *
   * @param x the input to label
   * @return a vector of probabilities
   * given each label.
   * <p/>
   * This is typically of the form:
   * [0.5, 0.5] or some other probability distribution summing to one
   */
  public INDArray output(INDArray x) {
    List<INDArray> activations = feedForward(x);


    //last activation is input
    return activations.get(activations.size() - 1);
  }


  /**
   * Reconstructs the input.
   * This is equivalent functionality to a
   * deep autoencoder.
   *
   * @param x        the input to transform
   * @param layerNum the layer to output for encoding
   * @return a reconstructed matrix
   * relative to the size of the last hidden layer.
   * This is great for data compression and visualizing
   * high dimensional data (or just doing dimensionality reduction).
   * <p/>
   * This is typically of the form:
   * [0.5, 0.5] or some other probability distribution summing to one
   */
  public INDArray reconstruct(INDArray x, int layerNum) {
    List<INDArray> forward = feedForward(x);
    return forward.get(layerNum - 1);
  }


  /**
   * Prints the configuration
   */
  public void printConfiguration() {
    StringBuilder sb = new StringBuilder();
    int count = 0;
    for (NeuralNetConfiguration conf : getLayerWiseConfigurations().getConfs()) {
      sb.append(" Layer " + count++ + " conf " + conf);
    }

    log.info(sb.toString());
  }


  /**
   * Assigns the parameters of this model to the ones specified by this
   * network. This is used in loading from input streams, factory methods, etc
   *
   * @param network the network to getFromOrigin parameters from
   */
  public void update(MultiLayerNetwork network) {
    this.defaultConfiguration = network.defaultConfiguration;
    this.input = network.input;
    this.labels = network.labels;
    this.weightTransforms = network.weightTransforms;
    this.visibleBiasTransforms = network.visibleBiasTransforms;
    this.hiddenBiasTransforms = network.hiddenBiasTransforms;
    this.layers = ArrayUtils.clone(network.layers);


  }


  /**
   * Sets the input and labels and returns a score for the prediction
   * wrt true labels
   *
   * @param input  the input to score
   * @param labels the true labels
   * @return the score for the given input,label pairs
   */
  @Override
  public double score(INDArray input, INDArray labels) {
    feedForward(input);
    setLabels(labels);
    Evaluation eval = new Evaluation();
    eval.eval(labels, labelProbabilities(input));
    return eval.f1();
  }

  /**
   * Returns the number of possible labels
   *
   * @return the number of possible labels for this classifier
   */
  @Override
  public int numLabels() {
    return labels.columns();
  }


  /**
   * Sets the input and labels and returns a score for the prediction
   * wrt true labels
   *
   * @param data the data to score
   * @return the score for the given input,label pairs
   */
  public double score(DataSet data) {
    feedForward(data.getFeatureMatrix());
    setLabels(data.getLabels());
    return score();
  }


  @Override
  public void fit() {
    fit(input, labels);
  }

  @Override
  public void update(Gradient gradient) {

  }

  /**
   * Score of the model (relative to the objective function)
   *
   * @return the score of the model (relative to the objective function)
   */
  @Override
  public double score() {
    if (getOutputLayer().getInput() == null)
      feedForward();
    return getOutputLayer().score();
  }

  @Override
  public void setScore() {

  }

  @Override
  public void accumulateScore(double accum) {

  }

  /**
   * Score of the model (relative to the objective function)
   *
   * @param param the current parameters
   * @return the score of the model (relative to the objective function)
   */

  public double score(INDArray param) {
    INDArray params = params();
    setParameters(param);
    double ret = score();
    double regCost = 0.5f * defaultConfiguration.getL2() * (double) Transforms.pow(mask.mul(param), 2).sum(Integer.MAX_VALUE).element();
    setParameters(params);
    return ret + regCost;
  }


  /**
   * Merges this network with the other one.
   * This is a weight averaging with the update of:
   * a += b - a / n
   * where a is a matrix on the network
   * b is the incoming matrix and n
   * is the batch size.
   * This update is performed across the network neuralNets
   * as well as hidden neuralNets and logistic neuralNets
   *
   * @param network   the network to merge with
   * @param batchSize the batch size (number of training examples)
   *                  to average by
   */
  public void merge(MultiLayerNetwork network, int batchSize) {
    if (network.layers.length != layers.length)
      throw new IllegalArgumentException("Unable to merge networks that are not of equal length");
    for (int i = 0; i < getnLayers(); i++) {
      Layer n = layers[i];
      Layer otherNetwork = network.layers[i];
      n.merge(otherNetwork, batchSize);

    }

    getOutputLayer().merge(network.getOutputLayer(), batchSize);
  }


  /**
   * Note that if input isn't null
   * and the neuralNets are null, this is a way
   * of initializing the neural network
   *
   * @param input
   */
  public void setInput(INDArray input) {
    if (input != null && this.layers == null)
      this.initializeLayers(input);
    this.input = input;

  }

  private void initMask() {
    setMask(Nd4j.ones(1, pack().length()));
  }


  /**
   * Get the input layer
   *
   * @return
   */
  public Layer getInputLayer() {
    return getLayers()[0];
  }

  /**
   * Get the output layer
   *
   * @return
   */
  public Layer getOutputLayer() {
    return getLayers()[getLayers().length - 1];
  }


  /**
   * Sets parameters for the model.
   * This is used to manipulate the weights and biases across
   * all neuralNets (including the output layer)
   *
   * @param params a parameter vector equal 1,numParameters
   */
  public void setParameters(INDArray params) {
    int idx = 0;
    for (int i = 0; i < getLayers().length; i++) {
      Layer layer = getLayers()[i];
      int range = layer.numParams();
      layer.setParams(params.get(NDArrayIndex.interval(idx, range + idx)));
      idx += range;
    }

  }


  /**
   * Feed forward with the r operator
   *
   * @param v the v for the r operator
   * @return the activations based on the r operator
   */
  public List<INDArray> feedForwardR(List<INDArray> acts, INDArray v) {
    List<INDArray> R = new ArrayList<>();
    R.add(Nd4j.zeros(input.slices(), input.columns()));
    List<Pair<INDArray, INDArray>> vWvB = unPack(v);
    List<INDArray> W = MultiLayerUtil.weightMatrices(this);

    for (int i = 0; i < layers.length; i++) {
      ActivationFunction derivative = getLayers()[i].conf().getActivationFunction();
      //R[i] * W[i] + acts[i] * (vW[i] + vB[i]) .* f'([acts[i + 1])
      R.add(R.get(i).mmul(W.get(i)).addi(acts.get(i)
          .mmul(vWvB.get(i).getFirst().addRowVector(vWvB.get(i).getSecond())))
          .muli((derivative.applyDerivative(acts.get(i + 1)))));
    }

    return R;
  }


  /**
   * Feed forward with the r operator
   *
   * @param v the v for the r operator
   * @return the activations based on the r operator
   */
  public List<INDArray> feedForwardR(INDArray v) {
    return feedForwardR(feedForward(), v);
  }

  /**
   * Do a back prop iteration.
   * This involves computing the activations, tracking the last neuralNets weights
   * to revert to in case of convergence, the learning rate being used to iterate
   * and the current epoch
   *
   * @param v the v in gaussian newton vector g * v
   * @return whether the training should converge or not
   */
  protected List<Pair<INDArray, INDArray>> backPropGradientR(INDArray v) {
    //feedforward to compute activations
    //initial error
    //log.info("Back prop step " + epoch);
    if (mask == null)
      initMask();
    //precompute deltas
    List<INDArray> deltas = computeDeltasR(v);
    //compute derivatives and gradients given activations


    List<Pair<INDArray, INDArray>> list = new ArrayList<>();

    for (int l = 0; l < getnLayers(); l++) {
      INDArray gradientChange = deltas.get(l);

      if (gradientChange.length() != getLayers()[l].getParam(DefaultParamInitializer.WEIGHT_KEY).length())
        throw new IllegalStateException("Gradient change not equal to weight change");


      //update hidden bias
      INDArray deltaColumnSums = deltas.get(l).mean(0);
      if (deltaColumnSums.length() != layers[l].getParam(DefaultParamInitializer.BIAS_KEY).length())
        throw new IllegalStateException("Bias change not equal to weight change");


      list.add(new Pair<>(gradientChange, deltaColumnSums));


    }

    INDArray pack = pack(list).addi(mask.mul(defaultConfiguration.getL2())
        .muli(v)).addi(v.mul(layerWiseConfigurations.getDampingFactor()));
    return unPack(pack);

  }


  public INDArray getLabels() {
    return labels;
  }

  public INDArray getInput() {
    return input;
  }


  public Map<Integer, MatrixTransform> getWeightTransforms() {
    return weightTransforms;
  }


  public void setLabels(INDArray labels) {
    this.labels = labels;
  }


  public Map<Integer, MatrixTransform> getHiddenBiasTransforms() {
    return hiddenBiasTransforms;
  }

  public Map<Integer, MatrixTransform> getVisibleBiasTransforms() {
    return visibleBiasTransforms;
  }

  public int getnLayers() {
    return layerWiseConfigurations.getHiddenLayerSizes().length + 1;
  }

  public Layer[] getLayers() {
    return layers;
  }

  public void setLayers(Layer[] layers) {
    this.layers = layers;
  }

  public INDArray getMask() {
    return mask;
  }

  public void setMask(INDArray mask) {
    this.mask = mask;
  }

  public static class ParamRange implements Serializable {
    private int wStart, wEnd, biasStart, biasEnd;

    private ParamRange(int wStart, int wEnd, int biasStart, int biasEnd) {
      this.wStart = wStart;
      this.wEnd = wEnd;
      this.biasStart = biasStart;
      this.biasEnd = biasEnd;
    }

    public int getwStart() {
      return wStart;
    }

    public void setwStart(int wStart) {
      this.wStart = wStart;
    }

    public int getwEnd() {
      return wEnd;
    }

    public void setwEnd(int wEnd) {
      this.wEnd = wEnd;
    }

    public int getBiasStart() {
      return biasStart;
    }

    public void setBiasStart(int biasStart) {
      this.biasStart = biasStart;
    }

    public int getBiasEnd() {
      return biasEnd;
    }

    public void setBiasEnd(int biasEnd) {
      this.biasEnd = biasEnd;
    }
  }


}



