from django.conf.urls import url
from detect import views

urlpatterns = [
        url(r'^save/$', views.save_data),
        url(r'^get/$', views.get_detection),
]