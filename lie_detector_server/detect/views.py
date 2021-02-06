from django.shortcuts import render
import json
from django.http import HttpResponse, JsonResponse
from datetime import datetime
import os
from django.views.decorators.csrf import csrf_exempt
from io import StringIO

import pandas as pd
import numpy as np
from sklearn.preprocessing import MinMaxScaler
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

# Create your views here.
@csrf_exempt
def save_data(request):
    data = request.body.decode('utf-8')
    now = datetime.now()
    file_folder = 'files'
    file_name = '%02d%02d_%02d%02d%02d.tsv' %(now.month, now.day, now.hour, now.minute, now.second)
    if not os.path.exists(file_folder):
        os.makedirs(file_folder)
    file_path = os.path.join(file_folder, file_name)
    with open(file_path, 'w') as f:
        f.write(data)
    return HttpResponse('Success')

@csrf_exempt
def get_detection(request):
    data = request.body.decode('utf-8')
    d_f = StringIO(data)
    raw = pd.read_csv(d_f, sep='\t')
    curTime = 0
    mindwave = []
    heartrate = []
    result = []
    f_rslt = ['mw_mean\tmw_std\tmw_cf\tmw_kts\thr_mean\thr_std\thr_max\thr_min\thr_cf\thr_kts\n']
    for i in range(1, len(raw)):
        line = raw.iloc[i,]
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
    for rslt in result:
        for data in rslt:
            f_rslt.append('\t'+str(data))
        f_rslt.append('\n')
    r_f = StringIO(''.join(f_rslt))
    df = pd.read_csv(r_f, sep='\t')
    feature_names = ['mw_mean', 'mw_std', 'mw_cf', 'mw_kts', 'hr_mean', 'hr_std', 'hr_max', 'hr_min', 'hr_cf', 'hr_kts']
    model = pickle.load(open('model.pkl', 'rb'))
    scaler = pickle.load(open('scaler.pkl', 'rb'))
    df = scaler.transform(df[feature_names])
    predicted = model.predict(df)
    rlt = np.mean(predicted)
    if rlt < 1-rlt:  return HttpResponse(0)
    else: return HttpResponse(1)
   

