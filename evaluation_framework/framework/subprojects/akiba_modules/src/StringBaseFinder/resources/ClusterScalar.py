import sys
import json
import numpy as np
import argparse
import matplotlib.pyplot as plt

from sklearn.cluster import HDBSCAN

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('input_file', help='input file')
    parser.add_argument('output_file', help='output file')
    parser.add_argument('-e', help='epsilon of DBSCAN cluster model', default=0x40000)
    args = parser.parse_args()

    input_data = json.load(open(sys.argv[1]))
    np_data = np.reshape(input_data, (-1, 1))

    model = HDBSCAN(min_cluster_size=2, min_samples=1, cluster_selection_epsilon=float(args.e))

    clusters = model.fit_predict(np_data)

    result = {}
    for idx, label in enumerate(clusters):
        result.setdefault(label.item(), []).append(np_data[idx][0].item())

    with open (sys.argv[2], 'w') as f:
        json.dump(result, f)