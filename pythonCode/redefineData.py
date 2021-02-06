#%% Preparation
import os
import pandas as pd
import numpy as np
from sklearn.svm import SVC
from sklearn.model_selection import GridSearchCV, cross_val_score
from sklearn.preprocessing import MinMaxScaler
from sklearn.ensemble import RandomForestClassifier
from sklearn.neural_network import MLPClassifier
import pickle

def crest_factor(arr):
    return np.max(arr)/np.sqrt(np.mean([x**2 for x in arr]))

def kurtosis(arr):
    if np.std(arr) > 0:
        mean = np.mean(arr)
        sd = np.std(arr)
        return np.mean([((x-mean)/sd)**4 for x in arr])
    else:
        return 0

#%% Data Preprocessing
f_list = os.listdir('D:/Desktop/Gitlab/Lie_Detector/K.H.Park/lie_detector_server/files')

# Reading datas from files and merge them to one file
with open('D:/Desktop/Gitlab/Lie_Detector/K.H.Park/lie_detector_server/redefined_data.tsv', 'w') as f_rslt:
    f_rslt.write('classification\tmw_mean\tmw_std\tmw_cf\tmw_kts\thr_mean\thr_std\thr_max\thr_min\thr_cf\thr_kts\n')
    for c_f in f_list:
        f = pd.read_csv('D:/Desktop/Gitlab/Lie_Detector/K.H.Park/lie_detector_server/files/' + c_f, sep='\t')
        curTime = 0
        mindwave = []
        heartrate = []
        result = []
        for i in range(1, len(f)):
            line = f.iloc[i,]
            if line['time'] - curTime > 3000:
                curTime += 3000
                try:
                    rslt_mindwave = [np.mean(mindwave), 
                                     np.std(mindwave), 
                                     crest_factor(mindwave), 
                                     kurtosis(mindwave)]
                    rslt_heartrate = [np.mean(heartrate), 
                                      np.std(heartrate), 
                                      np.max(heartrate), 
                                      np.min(heartrate), 
                                      crest_factor(heartrate), 
                                      kurtosis(heartrate)]
                    result.append(rslt_mindwave + rslt_heartrate)
                except:
                    pass
            if not np.isnan(line['raw']):
                mindwave.append(line['raw'])
            if not np.isnan(line['heartrate']):
                heartrate.append(line['heartrate'])
        
        if 'true' in c_f: classification = '1'
        elif 'false' in c_f: classification = '0'
        print(classification)
        # mw_max\tmw_min\t
        for rslt in result:
            f_rslt.write(classification)
            for data in rslt:
                f_rslt.write('\t' + str(data))
            f_rslt.write('\n')
   
#%% Loading Data
df = pd.read_csv('D:/Desktop/Gitlab/Lie_Detector/K.H.Park/lie_detector_server/redefined_data.tsv', sep='\t')
feature_names = ['mw_mean', 'mw_std', 'mw_cf', 'mw_kts', 'hr_mean', 'hr_std', 'hr_max', 'hr_min', 'hr_cf', 'hr_kts']
scaler = MinMaxScaler()
scaler.fit(df[feature_names])
x_train = scaler.transform(df[feature_names])
y_train = df['classification']

#%% Modeling - Support Vector Machine
# Find the best parameters
svm = SVC(kernel='rbf', class_weight='balanced')
c_range = np.logspace(-5, 15, 11, base=2)
gamma_range = np.logspace(-9, 3, 13, base=2)
param_grid = [{'kernel':['rbf', 'linear'], 'C':c_range, 'gamma':gamma_range}]
grid = GridSearchCV(svm, param_grid, cv=3, n_jobs=-1)
grid.fit(x_train, y_train)
print(grid.best_params_)

#%% Modeling - Support Vector Machine
# Ten-fold Cross Validation
svm = SVC(kernel='rbf', class_weight='balanced', gamma=1.0, C=2.0)
scores = cross_val_score(svm, x_train, y_train, cv=10)
print(np.mean(scores))

#%% Modeling - Random Forest
# Find the best parameters
rf = RandomForestClassifier(n_jobs=-1, random_state=123456)
n_estimators_range = range(50, 200, 10)
param_grid = [{'n_estimators':n_estimators_range}]
grid = GridSearchCV(rf, param_grid, cv=3, n_jobs=-1)
grid.fit(x_train, y_train)
print(grid.best_params_)

#%% Modeling - Random Forest
# Ten-fold Cross Validation
rf = RandomForestClassifier(n_estimators=180, n_jobs=-1, random_state=123456)
scores = cross_val_score(rf, x_train, y_train, cv=10)
print(np.mean(scores))

#%% Modeling - Multi-layer Perceptron
# Find the best parameters
mlp = MLPClassifier(solver='sgd', activation='relu', random_state=123456, max_iter=200, verbose=10, learning_rate_init=.1)
alpha_range = np.logspace(-5, 5, 11, base=10)
hidden_layer_sizes_range = [(x,y) for x in range(10, 200, 10) for y in range(10, 200, 10)]
param_grid = [{'alpha':alpha_range, 'hidden_layer_sizes':hidden_layer_sizes_range}]
grid = GridSearchCV(mlp, param_grid=param_grid, cv=3, n_jobs=-1)
grid.fit(x_train, y_train)
print(grid.best_params_)

#%% Modeling - Multi-layer Perceptron
mlp = MLPClassifier(solver='sgd', activation='relu', alpha=1e-4, hidden_layer_sizes=(100,140), random_state=123456, max_iter=200, verbose=10, learning_rate_init=.1)
scores = cross_val_score(mlp, x_train, y_train, cv=10, scoring='recall')
print(np.mean(scores))

#%% Extract model and scaler
pickle.dump(scaler, open('D:/Desktop/Gitlab/Lie_Detector/K.H.Park/lie_detector_server/scaler.pkl', 'wb'))
rf.fit(x_train, y_train)
pickle.dump(rf, open('D:/Desktop/Gitlab/Lie_Detector/K.H.Park/lie_detector_server/model.pkl', 'wb'))

#%%
import os
path = 'D:/KHPARK/Desktop/Folder/'
if not os.path.exists(path):
    os.makedirs(path)
    
','.join(['hello'])

{x:x*2 for x in [2,3]}
