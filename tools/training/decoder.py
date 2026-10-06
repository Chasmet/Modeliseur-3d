"""CPU fine-tuning of the actual small TripoSR NeRF MLP; frozen INT4 encoder.

Original application implementation under the repository MIT license.
No surrogate fusion model and no dependency on GPU or PyTorch.
"""
import copy
import numpy as np
import onnx
from onnx import numpy_helper

ISO = 1 + np.log(25)


def sigmoid(x):
    return 1 / (1 + np.exp(-np.clip(x, -40, 40)))


class Decoder:
    def __init__(self, path):
        self.graph = onnx.load(str(path), load_external_data=True)
        initializers = {v.name: numpy_helper.to_array(v).copy() for v in self.graph.graph.initializer}
        self.names = []
        self.parameters = []
        for node in self.graph.graph.node:
            if node.op_type != "Gemm":
                continue
            attributes = {v.name: onnx.helper.get_attribute_value(v) for v in node.attribute}
            if attributes.get("transB", 0) != 1 or attributes.get("transA", 0) != 0:
                raise ValueError("Unsupported decoder matrix convention")
            self.names.extend(node.input[1:])
            self.parameters.extend([initializers[node.input[1]], initializers[node.input[2]]])
        if len(self.parameters) != 20 or self.parameters[-2].shape != (4, 64):
            raise ValueError("Expected the pinned ten-layer TripoSR decoder")
        self.initial = [v.copy() for v in self.parameters]
        self.moments = [np.zeros_like(v) for v in self.parameters]
        self.variances = [np.zeros_like(v) for v in self.parameters]
        self.step = 0

    def forward(self, x, tape=False):
        cache = []
        for i in range(0, len(self.parameters), 2):
            w, b = self.parameters[i:i+2]
            z = x @ w.T + b
            s = sigmoid(z)
            cache.append((x, z, s))
            x = z * s if i < len(self.parameters)-2 else z
        return (x, cache) if tape else x

    def loss_and_gradient(self, x, labels, anchor=1e-4, sample_weights=None):
        weights = self._sample_weights(labels, sample_weights)
        out, tape = self.forward(x, tape=True)
        logits = 2 * (out[:, 0] - ISO)
        loss = np.mean(weights*(np.logaddexp(0, logits) - labels * logits))
        derivative = np.zeros_like(out)
        derivative[:, 0] = weights*2 * (sigmoid(logits) - labels) / len(labels)
        gradients = self._backward(tape, derivative)
        return self._regularize(loss, gradients, anchor)

    def fused_loss_and_gradient(self, views, labels, anchor=1e-4, sample_weights=None):
        """Differentiate the same top-two-plus-mean four-view occupancy fusion.

        Each row must describe the same world point in all four image fields.
        This changes only the shared decoder, not the frozen image encoder.
        """
        if len(views) != 4 or any(len(x) != len(labels) for x in views):
            raise ValueError("Four corresponding feature batches are required")
        point_weights = self._sample_weights(labels, sample_weights)
        out, tape = self.forward(np.concatenate(views), tape=True)
        logits = 2*(out[:, 0].reshape(4, -1).astype(np.float64)-ISO)
        probabilities = sigmoid(logits)
        complements = sigmoid(-logits)
        order = np.argsort(logits, axis=0, kind="stable")
        weights = np.full(probabilities.shape, .05, dtype=probabilities.dtype)
        columns = np.arange(len(labels))
        weights[order[-1], columns] += .4
        weights[order[-2], columns] += .4
        fused = (weights*probabilities).sum(0)
        # Compute the complementary probability independently to avoid 1-1=0
        # and 0*log(0) on saturated fields. Keep the BCE gradient useful there.
        fused_complement = (weights*complements).sum(0)
        loss = -np.mean(point_weights*(labels*np.log(fused)+(1-labels)*np.log(fused_complement)))
        derivative_fused = point_weights*((1-labels)/fused_complement-labels/fused)/len(labels)
        derivative = np.zeros_like(out)
        derivative[:, 0] = (derivative_fused[None]*weights*2*probabilities*complements).reshape(-1)
        gradients = self._backward(tape, derivative)
        return self._regularize(loss, gradients, anchor)

    @staticmethod
    def _sample_weights(labels, weights):
        if not len(labels):
            raise ValueError("Nonempty batch required")
        result = np.ones(len(labels)) if weights is None else np.asarray(weights)
        if result.shape != (len(labels),) or not np.isfinite(result).all() or (result < 0).any() or not result.sum():
            raise ValueError("Use finite nonnegative per-point importance weights")
        return result

    def _backward(self, tape, derivative):
        gradients = [None] * len(self.parameters)
        for layer in range(len(tape)-1, -1, -1):
            x, z, s = tape[layer]
            if layer < len(tape)-1:
                derivative = derivative * (s + z * s * (1-s))
            index = layer * 2
            gradients[index] = derivative.T @ x
            gradients[index+1] = derivative.sum(axis=0)
            derivative = derivative @ self.parameters[index]
        return gradients

    def _regularize(self, loss, gradients, anchor):
        # Keep the RGB output layer fixed: the app projects the original photos.
        for i, (p, original) in enumerate(zip(self.parameters, self.initial)):
            loss += anchor * np.mean((p-original)**2)
            gradients[i] += (2 * anchor / p.size) * (p-original)
        gradients[-2][1:] = 0
        gradients[-1][1:] = 0
        return float(loss), gradients

    def update(self, gradients, learning_rate):
        self.step += 1
        norm = np.sqrt(sum(float((g.astype(np.float64)**2).sum()) for g in gradients))
        if not np.isfinite(norm):
            raise ValueError("Non-finite training gradient")
        scale = min(1., 1. / max(norm, 1e-12))
        for p, g, m, v in zip(self.parameters, gradients, self.moments, self.variances):
            g = g * scale
            m[:] = .9*m + .1*g
            v[:] = .999*v + .001*g*g
            p -= learning_rate * (m/(1-.9**self.step)) / (np.sqrt(v/(1-.999**self.step))+1e-8)

    def save(self, path):
        graph = copy.deepcopy(self.graph)
        values = dict(zip(self.names, self.parameters))
        for initializer in graph.graph.initializer:
            value = values.get(initializer.name, numpy_helper.to_array(initializer))
            initializer.CopyFrom(numpy_helper.from_array(value, initializer.name))
        onnx.checker.check_model(graph)
        onnx.save(graph, str(path))


def sample_planes(scene, coordinates):
    """Exact Android grid_sample(align_corners=False), zero padding, XY/XZ/YZ."""
    scene = np.asarray(scene).reshape(3, 40, 64, 64)
    out = np.zeros((len(coordinates), 120), np.float32)
    for plane, axes in enumerate(((0, 1), (0, 2), (1, 2))):
        pixels = (coordinates[:, axes]+1)*32-.5
        low = np.floor(pixels).astype(int)
        frac = pixels-low
        for a in (0, 1):
            for b in (0, 1):
                x, y = low[:, 0]+a, low[:, 1]+b
                valid = (x >= 0) & (x < 64) & (y >= 0) & (y < 64)
                weights = (frac[:, 0] if a else 1-frac[:, 0])*(frac[:, 1] if b else 1-frac[:, 1])
                out[valid, plane*40:(plane+1)*40] += scene[plane, :, y[valid], x[valid]] * weights[valid, None]
    return out
